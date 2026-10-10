# Cambios técnicos — 1.4.2 runtime

## Rendimiento y ciclo de vida
- Evita crear el recolector exclusivamente térmico si Android no expone la API necesaria o falta el servicio de energía.
- Sustituye las copias de listeners en cada tick por snapshots actualizados únicamente al registrar o retirar listeners.
- El diagnóstico desactivado y las paradas repetidas no escriben al control de timing JNI. Una sesión habilitada lo desactiva una sola vez.
- Revierte arranques parciales de scheduler/recolectores ante fallos de thread, Handler o rechazo de la primera publicación; permite reintentar sin loops retenidos.

## Java, memoria y lanzamiento
- Distingue memoria desconocida de margen medido insuficiente; conserva el heap explícito y no recomienda 256 MB cuando el presupuesto no lo permite.
- Aplica el techo de espacio de direcciones sin descontar dos veces la reserva de memoria nativa/Android/GPU.
- Compara claves JVM y rutas de agentes exactamente; conserva propiedades extendidas y overrides sin duplicar defaults de allocator/SPVC/agentes.
- Lee versiones Java legacy, build y early-access; trata metadata corrupta como runtime inválido y actualiza la caché tras reinstalación/selección.
- Restablece la ruta de bibliotecas entre runtimes y detiene la preparación si no se cargan libjli/libjvm obligatorias.
- Amplía la limpieza del servidor local de skins a errores previos al arranque de la JVM.

## Gráficos y puentes nativos
- Propaga fallos de inicialización, invalida tablas incompletas y evita dispatch con punteros nulos o renderer ausente.
- Separa inicialización de plataforma de la inicialización diferida del contexto GL; una ventana Vulkan NO_API no depende de que OpenGL pueda inicializarse.
- Valida símbolos EGL/OSMesa, conserva fallback a exportaciones de la biblioteca seleccionada y limpia cargas fallidas.
- Comprueba asignaciones/configuración/bind de API EGL y reduce la consulta de configuración exitosa de dos llamadas a una.
- Conserva glFinish en OSMesa y la ruta Vulkan sin API; no incorpora drivers nuevos ni ajustes específicos de una generación Mali.

## Validación y alcance
- Añade regresiones de lifecycle, JNI, argumentos/metadata/carga Java y fallos de loaders/contextos gráficos; compila las unidades nativas modificadas para ARM32/ARM64 mediante NDK.
- Diagnóstico detallado, resolución dinámica y experimentos JVM/prioridad permanecen opt-in; ADPF sigue desactivado sin duración CPU válida.
- Estas correcciones no constituyen mediciones de rendimiento físico. Instalación/migración, Minecraft/mods, drivers Mali, temperatura y comparación A/B requieren pruebas en dispositivo.
