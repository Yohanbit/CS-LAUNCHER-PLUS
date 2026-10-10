# Segunda ronda: rendimiento, Java/memoria y puentes nativos

Base verificada: `13f0d24f38494a3a745739c7f4fed601e88b6fc0`, que conserva la primera APK verificada (`4f17662f6be682db2a116bd651dcd041fa1029e3`). Rama nueva: `fix/mali-runtime-round2`. No fusionar en main/base ni retirar releases anteriores.

1. Inspeccionar costos y defectos concretos en ciclo de vida/trabajo periódico; implementar reducciones seguras con pruebas de código de producción.
2. Revisar runtime Java real, argumentos/memoria y fallos de lanzamiento, preservando opciones explícitas y el contrato AWT sin GL.
3. Revisar inicialización, errores y lifecycle EGL/OSMesa sin eliminar sincronización necesaria ni añadir drivers no probados.
4. Integrar, ejecutar regresiones host y compilación Android ARM32/64; inspeccionar firma/paquete/versionCode frente a APK anterior.
5. Publicar una prerelease nueva con notas solo de cambios técnicos de esta ronda.

Objetivo: familia Mali y backends compatibles, sin asumir equivalencia entre generaciones o drivers. No se anuncian ganancias de FPS sin medición física. El permiso del usuario no sustituye licencias de Minecraft o de terceros. Se modifica el launcher/código de fuentes disponibles, no código propietario sin permiso/licencia apropiados.
