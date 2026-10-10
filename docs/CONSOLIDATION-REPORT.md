# Informe técnico de consolidación 1.5.0

## Código y versiones de origen

| Versión | Etiqueta | Commit publicado | Release ID |
|---|---|---|---|
| 1.4.1 | `verified-1.4.1-38031765502` | `4f17662f6be682db2a116bd651dcd041fa1029e3` | 408739595 |
| 1.4.2 | `runtime-1.4.2-38032930792` | `70ef402cf049047f1015de3d901d82b1964687e9` | 408755712 |
| 1.4.3 | `vulkan11-1.4.3-38035591430` | `f7d53619d0d89b1ef5f54e2e4560285fdbd8a7ab` | 408783354 |

Base anterior a las tres versiones: `72f4b0f45fc9f44dbb771d6b6b30a38d8825eb25`. Base de continuación documental: `f942e25ce5efe08789eaeefe2fcd297d072872e8`, rama `fix/vulkan11-resume-validation`. La rama consolidada no sustituye main ni reescribe las ramas de origen.

Las tres versiones forman una historia lineal. Se compararon los contenidos completos de las transiciones: 67, 44 y 48 archivos modificados, respectivamente; 159 entradas históricas y 133 rutas distintas. Los patches truncados de GitHub no se utilizaron como evidencia suficiente: se recuperaron 315 snapshots y se verificaron contra sus SHA Git blob. La matriz de archivos se complementa con matrices funcionales de interfaz, runtime y código nativo; herencia de un archivo no equivale a certificación de su ejecución.

## Funciones conservadas por origen

### 1.4.1

- Medición acumulativa de submissions mediante ventanas independientes, invalidación por ciclo de vida y métricas detalladas de espera CPU opt-in.
- Resolución dinámica con sondeo reversible, validación de respuesta, recuperación, histéresis y restauración de la escala manual cuando no hay medición válida.
- Selección FPS/VSync reversible con backup; voto de refresco restringido a modos del tamaño físico actual.
- Políticas de rendimiento, scheduler opt-in con identidad de hilo y restauración de prioridad, API térmica pública y ajustes Java/entorno condicionados por runtime/backend.
- Presupuesto de memoria consultivo sin inflación silenciosa del heap; preservación de lanzamientos de instaladores sin renderer.

### 1.4.2

- Validación de la tabla de puentes y símbolos, rollback de loaders incompletos y comprobaciones de inicialización EGL.
- Carga GL diferida para rutas NO_API; entornos y políticas reconciliados tras fallback de carga de bibliotecas.
- Argumentos por claves exactas, selección de agentes por JAR y respeto de collector explícito.
- Distinción entre memoria desconocida e insuficiente; arranques de diagnósticos con rollback, cancelación por generación y ausencia de llamadas JNI al permanecer desactivados.

### 1.4.3

- Loader Vulkan con caché, validación de exports, reintento y handle raíz retenido; leases independientes de consumidores JNI/LWJGL.
- Arquitectura del proceso, guard de ABI del runtime y metadata Vulkan sin extrapolación de features o extensiones.
- Readiness atomic32 y observador acotado de primera submission, conectado al foreground y con callbacks inválidos tras pause/stop.
- Selección conservadora de renderer y aviso de intento manual de Zink sin certificar su backend.

## Correcciones de reconciliación

La persistencia publica dirty keys antes de recargar y evita reemplazar valores ajenos con un draft antiguo. El reset elimina solo las claves de ajustes compartidas y sanea backup; conserva el resto del JSON. Los controles siguen accesibles, incluido el opt-in explícito `experimentalModWorkarounds=false`. El default del perfil Extreme se conserva.

El lanzamiento utiliza un tokenizer ordenado que conserva espacios entre comillas, escapes y múltiples `=`. Los flags explícitos del usuario mantienen precedencia sobre los workarounds automáticos. Los edits mixin propios llevan backup/ownership para restaurar únicamente líneas propias intactas; los valores heredados sin autoría discernible no se borran.

La RAM se evalúa según el proceso; la selección/importación de runtimes filtra ABI y el guard final permanece. El extractor valida rutas y symlinks/hardlinks y rechaza entradas especiales. Se corrigen propiedades LWJGL inválidas y el diagnóstico de options lee la última ocurrencia. El fallback después de `dlopen` no se presenta como fallback transaccional de contexto EGL.

Las matrices funcionales y los documentos `CONSOLIDATION-*-VALIDATION.md` describen los métodos de prueba y límites de cada corrección. La regresión de extracción en CI utiliza commons-compress 1.24.0, xz 1.8 y commons-io 2.14.0 resueltos por Gradle antes de ejecutar las suites.

La disponibilidad de medición requiere contexto válido. Las superficies se publican bajo mutex y el render consume snapshots con referencias propias. El teardown utiliza bundles reales, balancea referencias y conserva objetos todavía ligados para reintento, sin cerrar el handle raíz Vulkan. El loader opcional limita el escaneo ARM64 y valida ELF/mmap/descriptor sin certificar la ABI privada del fabricante.

## Distribución e identidad

La versión consolidada utiliza `versionName=1.5.0`, `versionCode=10015000`, minSDK 21 y targetSDK 34. La distribución mantiene ARM32 (`armeabi-v7a`) y ARM64 (`arm64-v8a`). El APK conserva el paquete `com.cslaucher.plus.debug` y el certificado debug de las versiones fuente para comprobar continuidad de actualización. Una Release normal de GitHub no transforma esta identidad de pruebas en una firma de producción.

La referencia de actualización es la APK 1.4.3: SHA-256 `917609fb4b8695324610937ccae97640f8d620b279fa1c9b69f86d1f66368666`. El flujo bloquea la publicación cuando no se cumplen identidad, firma, versión creciente, SDK, ABI o integridad.

Los resultados de la compilación concreta, su commit, URL de ejecución, checksum del APK e inspección se registran en los artefactos de verificación de esa ejecución. Las pruebas de versiones anteriores no sustituyen las regresiones de esta integración.

## Límites de compatibilidad y entorno

- No hay pruebas físicas de instalación, actualización, Minecraft, mods, shaders, Mali, FPS, temperatura ni comportamiento de drivers. Las comprobaciones host/NDK y la inspección APK no equivalen a esas pruebas.
- El renderer distribuido es MobileGlues 2.0.0 para ambos ABI. Las rutas alternativas conservadas requieren bibliotecas y dependencias reales disponibles; no se aportan Mesa/Zink/Turnip/GL4ES/KW/LTW ni ANGLE standalone por cambiar una etiqueta o una variable.
- Vulkan 1.1 es el límite declarado del dispositivo objetivo. Los exports requeridos por el loader pertenecen a Vulkan 1.0; no se presume soporte de extensiones ni features 1.2/1.3. La carga de una biblioteca no demuestra creación satisfactoria de contexto.
- Vulkan nativo sin instrumentación de submissions muestra medición no disponible; no se fabrica FPS cero ni se usa esa ausencia como medición válida de resolución dinámica.
- ADPF permanece inactivo por ausencia de duración CPU válida del trabajo y pertenencia verificada del hilo. El intervalo de swap, su espera CPU y FPS no sustituyen esos datos.
- Los logs GC se configuran antes de iniciar la JVM. Los controles de diagnóstico explican su ciclo de aplicación; no se afirma cambio de flags en una JVM ya activa.
- La configuración Firebase ausente solo se omite mediante la opción explícita de compilación. Una clave CurseForge no suministrada no habilita esa API.
- No se convierte JVM/JIT en emulación ni se promete soporte de mods dinámicos mediante Native Image. No se redistribuye código propietario nuevo.
- El teardown queda diferido si otro hilo mantiene binding, OSMesa rechaza unbind o falla un destructor. Requiere cierre ordenado y nueva llamada terminate; no hay expulsión de contextos ajenos, destructor TLS ni reintento automático universal. No se afirma ausencia universal de leaks.
- El extractor no añade cuotas, rollback transaccional ni defensa contra un adversario concurrente dentro del directorio privado. Los backups de mixin no identifican autoría de una reescritura ajena idéntica a una línea propia.

## Conservación y limpieza de publicaciones

Los commits, tags, ramas y matrices conservan la procedencia. Las publicaciones fuente no se retiran antes de verificar la nueva Release y sus assets. La retirada posterior de publicaciones, si se ejecuta, no elimina los tags ni altera los commits. Los assets de la Release consolidada incluyen las referencias históricas y evidencia de integridad para identificar las tres fuentes después de esa retirada.
