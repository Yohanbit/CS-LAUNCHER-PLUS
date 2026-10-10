# Ronda 3 — Java, ABI de proceso y Vulkan declarado

## Estado / alcance

Implementación lista en `/tmp/unamed-work`, rama `fix/vulkan11-native-round3`, sin commits ni push. No modifiqué JNI, MainActivity, collectors, FpsCounter, superficies, workflows ni SDK/ABI de empaquetado. Tools sólo cambió en los dos bloques autorizados: advertencia de memoria de lanzamiento y lista de renderers. Otros cambios visibles en el árbol corresponden a trabajo concurrente.

No se presume un modelo de Mali ni un driver uniforme. No se afirmaron ganancias FPS, compatibilidad Sodium ni soporte universal Zink. Se conserva ARM32 y minSDK21.

## Evidencia de bugs y correcciones

### 1. No confundir un boolean Vulkan/GLES/nombre GPU con soporte de Zink

**Antes:** `DeviceCapability.hasMatureVulkan()` devolvía verdadero con Vulkan boolean + Mali/Adreno + GLES3. `Tools.getCompatibleRenderers()` no conocía la versión declarada. Eso no enumera versiones efectivas, extensiones, features físicos ni requisitos del Mesa empaquetado.

**Ahora:** `DeviceCapability.vulkanApiVersion` conserva la versión de `FeatureInfo.version` para el nombre exacto `FEATURE_VULKAN_HARDWARE_VERSION`, usando API24+ y 0 para desconocido/error. Helper puro `RuntimeVulkanSupport` valida y presenta el formato VK_MAKE_VERSION. El método legacy `hasMatureVulkan()` permanece por compatibilidad pero devuelve falso: no hay prueba física/contrato de requisitos para certificarlo. UI distingue versión **declarada** y requisitos del backend **no verificados**.

Tools permite mostrar Zink como intento manual únicamente si están las bibliotecas del paquete activo y Android declara hardware Vulkan con versión válida 1.0+. Esto incluye 1.1: **no se inventó mínimo 1.2/1.3 ni se declaró que todo Zink falle en 1.1**. Su etiqueta advierte explícitamente que los requisitos no están verificados. Un backend futuro que necesite más versión/extensiones deberá incorporar su contrato y probe real; un boolean no bastará.

### 2. No promover un backend Vulkan incierto mediante fallback automático

**Antes:** la lista de fallback podía insertar Zink al fallar un wrapper GL, aun sin validación del backend Vulkan, sólo por estar publicado en la lista de candidatos.

**Ahora:** `RuntimeRendererRules.candidates()` sólo conserva Zink si fue la selección explícita (incluidos aliases históricos). Los fallbacks de wrappers/selección desconocida omiten Zink. Un intento Zink explícito conserva los wrappers instalados como fallback si falla su carga, sin prometer recuperación de una creación de contexto posterior. JREUtils registra el intento/limitación. No cambió AWT/null: retorna antes de cualquier probing. Orden de entorno antes de dlopen, limpieza entre intentos y overrides `custom_env.txt` siguen intactos. Overrides explícitos pueden cambiar el backend real y la evaluación anterior no certifica ese override.

**Inventario del APK 1.4.2 real proporcionado:** SHA256 `14c3e0256ea2508800c2f79b7e251caef1950e774eebe9eead2f97307649c6a6`. En ambas ABI faltan `libOSMesa.so`, `libEGL_mesa.so`, `libglxshim.so`, `libgl4es_115.so`, `libltw.so` y `libng_gl4es.so`; `libmobileglues.so` existe: ARM64 ELF64/machine183/6.120.864 bytes y ARM ELF32/machine40/4.499.832 bytes. **No se agregó Mesa/Zink ni se hizo pasar el checkout por distribución real.** La ausencia de libvulkan.so en APK no significa ausencia del driver del sistema.

Inventario reproducible guardado: `round3-apk-inventory.log` en este mismo directorio.

### 3. Bitness real del proceso para memoria/JVM/JNI, no capacidad máxima del dispositivo

**Antes:** `Architecture.is64BitsDevice()` usaba `Build.SUPPORTED_64_BIT_ABIS`. Ese dato se utilizaba para límite de address-space, rutas `/system/lib64`/`/vendor/lib64`, selección y descarga de runtime/natives. Un proceso APK32 en dispositivo64 podía seleccionar JVM64 y omitir aviso de límite virtual32.

**Ahora:** APIs `is64BitProcess`, `is32BitProcess`, `getProcessArchitecture` usan `Process.is64Bit()` desde API23; API21-22 clasifican `os.arch` de la VM activa, sin reflection ni inferencia por listas del dispositivo. Desconocido queda `UNSUPPORTED_ARCH`; la advertencia/address-space se estrecha conservadoramente en API21-22 desconocido. `requireCompatibleRuntimeArchitecture()` rechaza antes de cambiar rutas/dlopen un JVM desconocido o de otra ABI. JREUtils usa ancho real para lib/lib64.

Se auditaron todos los callers del selector legacy `getDeviceArchitecture()` y `Tools.DEVICE_ARCHITECTURE`: descarga/selección de JRE, assets runtime, extracción/carpetas LWJGL/JNI, sello de caché natives y log del lanzamiento. El selector legacy delega al ABI del proceso; se conservan helpers específicos de capacidad hardware `is64BitsDevice/is32BitsDevice/isx86Device`. No se cambia una UI de hardware a un dato de proceso sin documentarlo: el perfil de rendimiento ahora refleja ABI/bitness del proceso que ejecutará el juego.

`DeviceCapability` incluye versión de esquema, ABI de proceso y VERSION_CODE en clave cache, evitando reutilizar un perfil con bitness incorrecto o inventario de una APK anterior. No introduce lectura /proc ni instalación de librerías.

## Tests / resultados

- **Nuevo** `sh tools/test-round3-runtime.sh`: **286 checks PASS**. Compila las clases de producción Architecture/DeviceCapability/RuntimeRendererRules/RuntimeVulkanSupport contra seams API Android controlables. Matriz API21/22/23/34, ARM32/64, x86/64, dispositivo64/proceso32, texto ancho discrepante con API pública, ABI desconocido/null y runtime incompatible. Clasificación locale-independent. Versiones Vulkan1.0/1.1/1.2/1.3, metadata inválida/variante, nombre FeatureInfo exacto, null/error, API24 guard; GPU Mali/Adreno+GLES3 no certifica. Intento manual, fallback cerrado, AWT null y claves cache por ABI/APK.
- Existentes `test-runtime-safety.sh`: **184 checks PASS** + **71 launch regression checks PASS**, incluidos flags reales en HotSpot21 host.
- `test-extreme-options.sh`: **37 PASS**. `test-dynamic-resolution.sh`: **298 + 102 PASS**.
- `python3 tools/native_graphics_round2_test.py /tmp/unamed-work`: suites loaders/contextos host **PASS**, sin modificar código nativo. Son regresiones compartidas, no validación física Vulkan ni autoría de cambios nativos.
- `git diff --check`: PASS.

Log Java/regresiones: `round3-runtime-tests.log`. Compilación host JDK21 con `--release 8`; warnings por target8 obsoleto/deprecated deliberado, sin errores.

## Límites / integración APK

No hay SDK/NDK ni teléfono Android accesible desde esta tarea; compilación APK completa/dispositivo quedan al padre/CI. Los seams Android ejecutan lógica Java real, no ART/HAL/controlador real. La persistencia org.json se usa sólo como seam de compilación; no se afirma test end-to-end de cache JSON.

El loader nativo coordinado por otro worker sólo valida exports globales Vulkan1.0 (también disponibles en1.1); eso **no enumera extensiones físicas**. Manual Zink queda no certificado. Un fallo tardío en contexto puede fallar cerrado sin relanzar automáticamente otro backend; sólo se garantiza el fallback de bibliotecas ya existente.

Queda fuera del bloque Tools autorizado el límite del slider RAM (`getMaximumRamAllocation`, línea1576) y defaults prefs, que aún usan capacidad de dispositivo; el lanzamiento ya advierte address-space del proceso y deja al usuario conservar su heap. Caso exótico x86 traducido y JVM `os.arch` desconocido deben validarse en dispositivo; el selector no elige una ABI del teléfono a ciegas.

Añadir `sh tools/test-round3-runtime.sh` a CI junto a tests existentes. Archivos nuevos deben incluirse en la integración (helper RuntimeVulkanSupport, test y soporte round3), además de cinco Java modificados.

## Licencias

No agregué dependencias, payloads ni código externo. Helpers/tests escritos para el repositorio bajo su licencia existente (`LICENSE`, GPLv3); notices de bibliotecas existentes sin cambios. Inventario del APK se limita a nombres/metadatos ELF, no redistribuye código binario.

## Incidencias de ejecución

Primer comando de regresión nativa omitió el argumento ROOT y terminó con IndexError; se leyó la firma, se repitió con `/tmp/unamed-work` y pasó. Sin cambios parciales de código ni commits. Sin errores en los tests Java.
