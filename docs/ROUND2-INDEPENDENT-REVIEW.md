# Ronda 2 — revisión de contratos de integración

## Cierre posterior: bloqueador corregido y revalidado

El hallazgo histórico de abajo está **cerrado antes de la publicación**. La revisión posterior confirmó que `pojavInit()` inicializa plataforma/JNI/ventana sin GL y que `pojavCreateContext()` retorna la ventana NO_API antes de cualquier inicialización GL. El contexto GL usa inicialización diferida fail-closed y reutiliza el bridge para contextos compartidos. La regresión completa init → hint → create pasa con GL fallido y renderer ausente; crear GL con ese backend inválido falla correctamente. El revisor ejecutó la suite focal con GCC y Clang, ambas aprobadas. La ejecución Android/NDK se verifica por separado en CI.

Se conserva el hallazgo inicial para documentar el error y su reproducción; sus referencias de líneas/test describen la instantánea anterior a la corrección, no el código publicado.

Árbol revisado: `/tmp/unamed-work`, rama `fix/mali-runtime-round2`, HEAD/base `13f0d24f38494a3a745739c7f4fed601e88b6fc0`, cambios locales sin commit. Revisión sólo de init fail-closed, loaders, Vulkan/NO_API, bibliotecas JVM obligatorias, cleanup del servidor de skins y claves exactas de argumentos. No se reabrió la primera ronda ni se modificó el repositorio.

## Bloqueador: GLFW_NO_API sigue condicionado a inicializar un backend OpenGL

**Ubicación del cambio:** `app_pojavlauncher/src/main/jni/egl_bridge.c:233–235`, junto con el init OpenGL incondicional de `pojavInit()` y `pojavInitOpenGL():166–213`.

La propagación de fallo OpenGL es correcta para ventanas OpenGL, pero ahora se exige antes de conocer qué API utilizará la ventana. El flujo real incluido en el repositorio es:

1. `jre_lwjgl3glfw/lwjgl-3.4.1/src/main/java/org/lwjgl/glfw/GLFW.java:880–891`: `glfwInit()` llama `pojavInit` y devuelve su resultado. Las variantes 3.3.3:845–856 y 3.4.2:844–855 hacen lo mismo.
2. Sólo después de un `glfwInit()` exitoso puede un consumidor realizar la secuencia normal `glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)` (`GLFW.java:1297–1303`) y crear su ventana.
3. El hint selecciona `RENDERER_VULKAN` en `egl_bridge.c:243–245`; `pojavCreateContext():338–342` devuelve entonces el ANativeWindow sin necesitar un contexto GL.
4. Sin embargo, `pojavInit()` ahora devuelve 0 si el backend GL seleccionado no inicializa. Por tanto, un consumidor Vulkan que respeta el resultado de `glfwInit()` abandona antes de poder seleccionar NO_API, aunque esa ventana no usa el backend fallido.

**Evidencia ejecutada, no hipótesis de dispositivo:** compilé las funciones de producción extraídas literalmente con los mismos dobles host del nuevo test, tanto de la base como del árbol actual. Inyecté un fallo de `br_init` con `AMETHYST_RENDERER=opengles_mobileglues`, una ventana nativa válida y JNI attachment válido; después, sólo si init tuvo éxito, seleccioné NO_API y comprobé el retorno del ANativeWindow.

```text
13f0d24:
GLFW init with injected unused GL-backend failure: 1; reached native Vulkan NO_API window: yes
current:
GLFW init with injected unused GL-backend failure: 0; reached native Vulkan NO_API window: no
```

Esto demuestra una dependencia/regresión de contrato; no afirma que un driver o dispositivo concreto falle. El test actual `tools/native_graphics_round2_test.py:252–255` comprueba hint→create directamente, omitiendo el `glfwInit()` previo, y no cubre este caso.

**Acción antes de publicar:** separar el init de plataforma/ventana del init OpenGL, de forma que una ventana NO_API no requiera un backend GL. Mantener fail-closed en el camino que realmente solicita GL, por ejemplo inicializándolo al crear el primer contexto GL. No basta con consultar `config_renderer == RENDERER_VULKAN` dentro de `pojavInit()`: en la secuencia estándar el hint aún no se ha enviado. Añadir una regresión que incluya init→hint NO_API→create con un backend GL inutilizable, y otra que siga rechazando el contexto GL correspondiente.

## Sin otros bloqueadores concretos dentro del alcance

- **Loaders EGL/OSMesa:** resolución del resolver seleccionado primero y `dlsym` del mismo handle como fallback; cada entrada usada por el bridge se valida. Los fallos de apertura/símbolos limpian punteros y cierran los handles fallidos, incluido libcutils en las rutas Kopper. Los handles exitosos permanecen abiertos: no observé punteros del bridge expuestos tras su descarga.
- **Bridge fail-closed para GL:** tabla completa exigida, tabla/configuración/coverage vaciadas al fallar el backend; get/create/make-current protegidos; referencia adicional de ANativeWindow liberada si falla esa inicialización. La reserva anterior a conocer NO_API es el único bloqueador encontrado.
- **`JREUtils.initJavaRuntime():97–101` / `RuntimeLibraryLoader.require():13–18`:** libjli debe cargar; libjvm exige éxito del nombre corto o de la ruta completa, parando en el primer éxito. Si ambos fallan se lanza un error antes de `VMLauncher`. No se silencian esos fallos; las bibliotecas opcionales conservan su tratamiento anterior.
- **Servidor skins:** `JREUtils.launchJavaVM():382–604` incluye preparación, renderer/env, argumentos, carga JVM y ejecución dentro de try/finally. Tras un arranque exitoso se registra ownership antes del resto de preparación (`:427–429`); los fallos pre-JVM y el retorno por crash pasan por stop. No identifiqué una fuga nueva en esos caminos ordinarios.
- **Claves exactas:** `RuntimeJvmArguments.optionKey/addDefault/removeKey` mantienen propiedades con prefijos similares independientes, reconocen +/-XX como la misma opción y agentes por ruta; los defaults allocator/SPVC/methods-injector no duplican una clave explícita. `purgeArg` ya no borra propiedades extendidas. El tratamiento anterior de Xmx/Xms/control gráfico sigue explícitamente forzado por el launcher; no se promete preservar un override en esos casos. El parser histórico de texto y los argumentos completos de manifests no se cambiaron en esta ronda.

## Validación y límites

Reejecutados en el árbol actual:

- `sh tools/test-runtime-safety.sh`: **184 checks + 71 regresiones PASS**, incluida validación de flags en HotSpot host Java 21.
- `sh tools/test-native-graphics-round2.sh`: **PASS host** para loaders, tabla/dispatch/init y context setup; NDK ARM32/ARM64 **SKIP** por `ANDROID_NDK_HOME` ausente.
- `git diff --check`: PASS.
- Reproductor adicional base/actual anterior: confirma el cambio de contrato NO_API.

Las pruebas runtime compilan los helpers puros, no `JREUtils` completo ni el launch Android. El try/finally real se revisó estáticamente; la compilación Android y el NDK/link completo quedan para CI. HotSpot host no valida todos los runtimes Android; experimentos JVM continúan default-off y el informe runtime documenta esa limitación. No hay afirmaciones de rendimiento, FPS o ejecución en dispositivo.

Se consultaron los informes compartidos `round2-runtime.md`, `round2-overhead.md` y `round2-native/round2-native.md`. Los números 58/71 y el fallo inicial de header en el informe overhead corresponden a instantáneas anteriores: las pruebas actuales contienen 71 regresiones y el fixture nativo actual pasa.
