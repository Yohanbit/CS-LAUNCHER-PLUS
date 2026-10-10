# Ronda 3 — cargador Vulkan y disponibilidad de contadores nativos

## Alcance y estado

Cambios integrados y acotados en `/tmp/unamed-work`, rama `fix/vulkan11-native-round3`, sobre el checkpoint `1d630a8` que conserva la entrega 1.4.2. No se hicieron commits, pushes ni publicaciones. No se modificaron Java, workflow, `frame_metrics.c/h`, drivers empaquetados o sincronización OSMesa. Se conserva `glFinish`, inicialización GL diferida y el orden GLFW `init → hint NO_API → create` independiente de GL.

Archivos nativos modificados: `app_pojavlauncher/src/main/jni/egl_bridge.c` y los dos cambios mínimos autorizados —declaración y llamada— en `jvm_hooks/lwjgl_dlopen_hook.c`. Los fixtures `tools/native_bridge_test.py` y `tools/native_graphics_round2_test.py` recibieron adaptaciones mínimas autorizadas al nuevo loader/snapshot, además de dos casos de fallo Vulkan en el segundo.

## 1. Carga validada, reintentos y caché sincronizada

El código anterior trataba cualquier `VULKAN_PTR` del entorno como handle confiable mediante `strtoul`. Un fallo `dlopen` publicaba `"0"`; la presencia de esa cadena impedía reintentar. Las consultas simultáneas hacían `getenv/check/dlopen` sin sincronización. No había validación de exports Vulkan.

Ahora una caché interna protegida por mutex conserva el handle abierto y validado. `VULKAN_PTR` es exclusivamente salida ABI para Mesa: nunca se convierte una cadena arbitraria en un puntero. Antes de una primera carga se elimina el valor stale; el handle válido se publica hexadecimal con tamaño `uintptr_t`, comprobación `snprintf/setenv` y sin escrituras repetidas si el valor ya coincide. Los fallos de limpieza/publicación se diagnostican y rechazan; los handles nuevos no publicados se cierran. Un fallo posterior de publicación no descarga un root que ya tiene consumidores. Las operaciones de entorno efectuadas por este loader están serializadas; no se afirma sincronizar escrituras externas ajenas a ese contrato.

`dlopen` usa `RTLD_NOW | RTLD_LOCAL`. Se validan exactamente cuatro exports globales Vulkan 1.0, disponibles también en Vulkan 1.1: `vkGetInstanceProcAddr`, `vkCreateInstance`, `vkEnumerateInstanceExtensionProperties` y `vkEnumerateInstanceLayerProperties`. Cada lookup comprueba tanto dirección como `dlerror`. No se exigen APIs Vulkan 1.2/1.3, ni se fuerza una versión del driver. Las dos rutas GL dependientes de Vulkan rechazan un loader fallido antes de instalar/inicializar el bridge. Los fallos no se cachean como éxito y permiten reintentos.

El camino opcional Turnip ya existente conserva su selección/namespace; no se amplió a Mali. Un candidato opcional inválido se cierra y permite intentar el loader normal. No se agregaron bibliotecas ni código externo.

## 2. Ownership real: root independiente y leases de consumidores

Un mutex no evita que un consumidor descargue el handle. La fuente pública del fork LWJGL muestra `getVulkanDriverHandle → Library.createFromHandle`; `VK.destroy()` llama a `SharedLibrary.free()`. Por eso una caché única entregada directamente a esos consumidores podía quedar stale.

`maybe_load_vulkan()` entrega una referencia **prestada** para uso interno; consultas repetidas no hacen nuevos `dlopen` ni incrementan referencias. El root se conserva durante el proceso. `load_vulkan()` conserva su ABI C y usa ese camino.

`acquire_vulkan_loader()` entrega una referencia **propiedad del consumidor**, que su `dlclose/free` consume. La ruta del loader se resuelve con `dladdr(vkGetInstanceProcAddr)` y queda cacheada bajo el mutex; el pathname permanece válido gracias al root retenido. Se retiene la misma biblioteca con `dlopen` de esa ruta, o `linker_ns_dlopen` en el namespace opcional existente. Se exige que el handle resultante sea exactamente el root: una referencia diferente se cierra y se rechaza. Un fallo de retención no invalida el root. JNI `VK_getVulkanDriverHandle` y el hook `ndlopen_bugfix` usan el camino propietario; los consumidores internos GL siguen usando el prestado.

No se usa `RTLD_NODELETE`, ni se introduce un requisito adicional Android 23+. Tradeoff explícito: una referencia root por proceso se retiene deliberadamente; cada lease externo requiere el `free/dlclose` habitual del consumidor. No hay referencias adicionales por consulta interna ni trabajo nuevo por frame.

## 3. Snapshot de disponibilidad para el watcher

JNI añadido: `Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage()`.

- `0 UNREADY`: plataforma sin GL listo, selección GL pendiente o fallo.
- `1 COUNTED`: bridge válido que cuenta **submissions**, no presentación física.
- `2 NATIVE_VULKAN_UNSUPPORTED`: selección explícita `GLFW_NO_API`; ese camino carece de contador nativo instrumentado.

Se usa un snapshot atomic32 independiente; el getter no lee `config_renderer` plano ni punteros del bridge. Las cargas/escrituras son relaxed porque solo publican el enum: no autorizan acceso a objetos gráficos. Escrituras únicamente en init/hint/context/terminate; getter sin mutex, loader, reloj, logs, asignaciones o llamadas gráficas. Un static assert exige atomic32 lock-free. El contrato existente FPS/total y los archivos `frame_metrics` no cambian.

La transición GL → NO_API borraba coverage; al volver a GL, el bridge reutilizado no lo restauraba. Ahora un contexto GL exitoso restaura la cobertura EGL/OSMesa y el snapshot, sin repetir init del backend. Una selección GL desconocida tampoco puede reutilizar un dispatch stale.

## Validación ejecutada

Nuevo entrypoint: `tools/test-vulkan-loader-round3.sh`; fixture: `tools/vulkan_loader_round3_test.py`. Ejecuta funciones de producción extraídas, incluyendo JNI y el consumidor hook, no una reimplementación de decisiones.

- **GCC y Clang: PASS**. Casos: entorno ausente/stale/malformado, puntero completo 64 bits, fallo dlopen/dependencias, cada export global ausente, dirección no nula con `dlerror`, fallos unsetenv/setenv, recuperación, publicación cacheada, fallback opcional y retención en namespace correcto.
- **Ownership: PASS** con dobles refcount: root + dos consumidores JNI/hook, destrucción independiente, 100 ciclos acquire/free, fallos de retención, `dladdr` y handle distinto, root conservado.
- **Linker ELF host real: PASS**: dos consumidores, 20 ciclos init/destroy y 1.000 consultas prestadas. Después de sus `dlclose` el root/exports siguen válidos; el único cierre final del root, exclusivamente de prueba, dispara exactamente una descarga/destructor. Esto detecta referencias extra filtradas por consultas internas.
- **Concurrencia loader: PASS**: 20 rondas × 32 hilos × 200 consultas = 128.000 solicitudes; exactamente una apertura, cuatro lookups y una publicación por caché nueva.
- **Disponibilidad: PASS**: init/GL/fail/NO_API/GL-return, fail-closed de Vulkan antes de GL y reutilización del bridge. Ocho lectores × 300.000 consultas durante 30.000 transiciones leen únicamente enum 0/1/2.
- **ASAN + UBSAN: PASS** en los nuevos fixtures, incluido el linker real. No se ejecutó TSAN ni un driver Android.
- Suites heredadas: native-bridge (7 casos), graphics-round2 (loaders/contextos/NO_API), measurement (incluye 400.000 submissions y probes freestanding ARM), runtime-safety (184 + 71), extreme-options (37), dynamic-resolution (298 + 102): **PASS**.
- `git diff --check`: **PASS**.

Con `NATIVE_REQUIRE_NDK=1`, la ausencia local de `ANDROID_NDK_HOME` produce el fallo requerido, comprobado. En CI, el nuevo script compila **translation units completas** `egl_bridge.c` y `jvm_hooks/lwjgl_dlopen_hook.c` para ARM32/ARM64, activa el macro opcional existente en ARM64 y rechaza helpers atómicos `__atomic_/__sync_` mediante `llvm-nm`. Sin SDK/NDK local, esa compilación real y la APK deben validarse en CI; no se reportan como ejecutadas aquí.

## Bibliotecas reales y Vulkan 1.1

Se inventarió la APK local publicada proporcionada, no solo el checkout: `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/browser_downloads/Unamed.launcher (1).apk`, SHA-256 `14c3e0256ea2508800c2f79b7e251caef1950e774eebe9eead2f97307649c6a6`.

Tiene 32 entradas `lib/**/*.so`. No existen entradas con `libOSMesa`, `libEGL_mesa`, `libglxshim` o `libvulkan` en todo el ZIP. Sí contiene MobileGlues en ARM64/ARM32. Por tanto el bloqueo concreto de la ruta Mesa/Zink integrada en esa entrega es **bibliotecas ausentes**, no un mínimo Vulkan demostrado de una Mesa empaquetada. No se atribuye versión/requisitos a un binario inexistente. Plugins o bibliotecas externas no proporcionadas quedan sin verificar.

La documentación actual Zink distingue versión Vulkan, features y extensiones, con requisitos adicionales por nivel OpenGL. Vulkan 1.1 por sí solo no prueba compatibilidad ni incompatibilidad de todo Zink. Tampoco rebajar una variable adapta un binario moderno ni crea extensiones faltantes. El cambio de loader exige solo globals 1.0/1.1 y deja los requisitos reales del backend/device **UNKNOWN** cuando no hay evidencia.

## Límites y evidencias

Sin teléfono Mali, SDK/NDK ni ejecución Android local. No se garantiza FPS, compatibilidad de juegos/mods, Vulkan 1.1 físico o equivalencia de sincronización GPU. No se cambiaron `glFinish`, opciones Vulkan de versiones, fences ni backend. La recuperación de superficies y binding OSMesa siguen fuera de esta ronda.

Logs e inventario reproducible: `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/round3-native-evidence/` (`vulkan-round3-{gcc,clang,sanitize,existing,required-ndk}.log`, `published-apk-inventory.json`). Los primeros fallos de extracción JNI/fixture heredado y un error de indentación al archivar inventario fueron corregidos; las suites finales e inventario se regeneraron.

Referencias públicas consultadas, sin copiar código al producto:

- https://docs.mesa3d.org/drivers/zink.html
- https://raw.githubusercontent.com/PojavLauncherTeam/lwjgl3/3.3.1/modules/lwjgl/vulkan/src/main/java/org/lwjgl/vulkan/VK.java
- https://raw.githubusercontent.com/PojavLauncherTeam/lwjgl3/3.3.1/modules/lwjgl/core/src/main/java/org/lwjgl/system/Library.java
- https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md

Los cambios quedan cerrados para revisión/compilación integrada. Notas de release separadas: solo modificaciones técnicas impersonales; este documento contiene pruebas, límites y pendientes.
