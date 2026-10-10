# Cambios técnicos — 1.4.3

## Vulkan y puentes nativos
- Valida símbolos globales del loader Vulkan, sincroniza su caché y permite reintentos tras fallos.
- Mantiene la referencia raíz del loader y entrega referencias independientes a consumidores JNI/LWJGL.
- Añade cobertura JNI atómica que diferencia inicialización pendiente, submissions contados y ventanas Vulkan nativas.
- Restaura cobertura al pasar de una ventana NO_API a un contexto GL instrumentado.

## Arquitectura y ciclo de vida
- Selecciona rutas nativas y runtimes según el ABI efectivo del proceso.
- Rechaza runtimes Java incompatibles antes de preparar sus bibliotecas.
- Separa metadatos Vulkan declarados por Android de los requisitos específicos del backend.
- Limita el watcher del primer submission al estado foreground e invalida callbacks obsoletos.

## Validación y distribución
- Añade regresiones de arquitectura, metadata Vulkan, watcher y propiedad/concurrencia del loader.
- Fija la APK 1.4.2 de referencia por release y checksum para la comparación de actualización.
- Bloquea la publicación si no coinciden paquete, certificado o minSDK, o si el versionCode no aumenta.
- Exige ARM32/ARM64 y minSDK 21 en la inspección de la APK.
- Sube la APK únicamente después de superar la inspección de firma, empaquetado y continuidad.
- Añade pruebas de regresión de los bloqueos de publicación.
- Valida parsing de identidad, errores de firma y propagación de fallos a la ejecución de publicación.
- Excluye Vulkan y AAudio de las dependencias de sistema disponibles para la inspección del renderer en minSDK 21.
