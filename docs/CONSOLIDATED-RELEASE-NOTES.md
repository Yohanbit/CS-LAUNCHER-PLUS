# Release 1.5.0

## Nuevas funciones

- Integra las rutas de medición, resolución dinámica, políticas de lanzamiento y loader Vulkan de 1.4.1, 1.4.2 y 1.4.3.
- Conserva ventanas independientes de submissions para HUD, diagnóstico y resolución dinámica, con invalidación por ciclo de vida.
- Conserva el observador de primera submission en foreground y la identificación de rutas nativas sin medición disponible.

## Optimizaciones

- Mantiene caché y validación de exports del loader Vulkan, con referencia raíz retenida y leases independientes JNI/LWJGL.
- Mantiene resolución dinámica mediante sondeo reversible, comprobación de respuesta, histéresis y recuperación de escala.
- Mantiene scheduler acotado, ajustes G1 compatibles y `mesa_glthread` como opciones experimentales condicionadas por perfil y backend.
- Mantiene diagnóstico opt-in sin consultas JNI de muestreo cuando permanece desactivado.

## Correcciones

- Guarda únicamente las claves de ajustes editadas antes de recargar preferencias, sin sobrescribir valores ajenos del estado compartido.
- Restablece las claves compartidas durante el reset y sanea su backup sin borrar otros datos del estado compartido.
- Conserva el pico térmico ordinal de la sesión durante pause/resume.
- Conserva el orden de argumentos Java, valores entre comillas, espacios y múltiples signos `=`; mantiene precedencia de opciones explícitas.
- Calcula defaults y presupuesto RAM según el ABI del proceso y filtra runtimes incompatibles antes de su selección.
- Valida contención de rutas y destinos de symlinks/hardlinks durante extracción de runtimes tar.xz; rechaza entradas de dispositivos especiales.
- Corrige propiedades nativas LWJGL inválidas en lanzamientos sin renderer y utiliza la última ocurrencia al diagnosticar opciones de Minecraft.
- Publica cobertura de medición después de crear contexto válido y la revoca al terminar; un fallo de contexto compartido no invalida el principal.
- Serializa la publicación de superficies y consume snapshots con referencias propias desde el hilo de render.
- Conecta destructores de bundles nativos con teardown diferido cuando persisten bindings o fallan destructores.
- Acota el escaneo ARM64 y valida rangos ELF, mmap y ownership de descriptores en el loader de driver opcional.

## Interfaz y configuración

- Actualiza la tarjeta de heap solicitado desde el borrador de ajustes.
- Precisa la aplicación en vivo de DRS, el inicio de muestreo al reanudar y la aplicación de logs GC al siguiente lanzamiento de JVM.
- Expone `experimentalModWorkarounds` como opt-in para workarounds de mods, con conservación de ajustes ajenos y restauración de cambios propios.
- Evita seleccionar Mesa/Zink automáticamente como última alternativa; conserva la selección manual de rutas elegibles.
- Mantiene selección FPS/VSync con backup y restauración del modo Original.

## Compatibilidad

- Mantiene minSDK 21, targetSDK 34 y distribución ARM32/ARM64.
- Mantiene la identidad y firma debug de actualización; incrementa `versionCode` a 10015000.
- Distribuye MobileGlues 2.0.0 y bundles nativos Android de LWJGL/SDL fijados por checksum.
