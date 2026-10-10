# Cambios técnicos — 1.4.3

## Vulkan y puentes nativos
- Validación de símbolos globales y errores de carga del loader Vulkan.
- Caché interna sincronizada del loader, limpieza de intentos fallidos y reintentos de carga.
- Separación de referencias internas y referencias de consumidores JNI/LWJGL para controlar la vida útil de los handles.
- Señal JNI de cobertura que distingue instrumentación pendiente, submissions contados y ventanas Vulkan nativas.
- Restauración de cobertura al cambiar de una ventana NO_API a un contexto GL instrumentado.

## Arquitectura y selección de renderer
- Detección del ABI y ancho efectivos del proceso para selección de JVM, extracción de bibliotecas y rutas nativas.
- Validación temprana de compatibilidad entre el ABI del runtime Java y el proceso.
- Corrección del cálculo de espacio de direcciones y del aviso de memoria según el ancho del proceso.
- Registro y visualización de la versión Vulkan declarada por Android.
- Selección y fallback de renderer con separación entre metadatos Vulkan y requisitos específicos del backend.

## Ciclo de vida
- Watcher del primer submission limitado al estado foreground de la actividad.
- Cancelación de callbacks en pausa, stop y destroy, con generaciones para invalidar tareas obsoletas.
- Espera adaptativa durante inicialización GL y reevaluación de cobertura durante las lecturas.

## Pruebas
- Nuevas regresiones de ABI, capacidades Vulkan, selección de renderer y compatibilidad de runtimes.
- Nuevas pruebas de lifecycle del watcher, compatibilidad JNI, transiciones y reinicios del contador.
- Nuevas pruebas de fallos, concurrencia y propiedad de referencias del loader Vulkan.
