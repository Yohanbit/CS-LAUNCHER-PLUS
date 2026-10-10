# Protocolo A/B Android — familia Mali

No contiene resultados de un teléfono. Guardar copia de perfiles, options.txt, mundos y controles antes de instalar. Verificar primero el resultado de comparación de certificado/paquete/versionCode adjunto a la prerelease; si no coincide, no asumir actualización conservando datos.

## Preparación

1. Registrar modelo, SoC/GPU Mali, Android, driver GLES/Vulkan, renderer y Java exacto. La familia Mali no es un driver uniforme.
2. Usar APK anterior identificada y nueva prerelease. Conservar idénticos Minecraft, loader, hashes/lista de mods, resource packs/shaders, mundo/copia de guardado, escena/cámara/recorrido, distancia render/simulación, resolución manual, guiScale, cap/VSync, RAM y Java.
3. Misma pantalla/refresco, brillo, batería/estado de carga, ambiente, red y apps de fondo. Dejar enfriar al mismo estado térmico inicial; no desactivar protecciones térmicas. Precalentar shaders/chunks igual y medir cold-cache aparte.
4. Primera serie: DRS y experiments apagados, diagnóstico detallado apagado. Segunda serie independiente: diagnóstico prendido para ambos. Tercera opcional: DRS prendido con los mismos límites. No mezclar configuraciones en una cifra.

## Ejecución y registro

- Alternar A-B-B-A, al menos tres repeticiones válidas por APK/serie; misma duración (p. ej. 10 minutos) y condiciones. Registrar cargas/fallos aunque invaliden una repetición, no ocultarlos.
- Pruebas funcionales antes del benchmark: iniciar juego, crear/abrir mundo, inventario, pausa, chat, touch/hotbar, cambio de orientación si admitido, salir/reanudar, surface recreation, Original/manual/unlimited, fallback de renderer cuando sea reproducible y memoria baja sin inducir pérdida de datos.
- Guardar contador de envíos por ventanas independientes. No llamarlo FPS presentado. Anotar FPS Minecraft/F3 por separado; refresco solicitado y efectivo por separado. No usar dumpsys gfxinfo como si fuera necesariamente el mundo nativo.
- CSV detallado registra espera CPU-wall de swap y gaps de llamadas, no trabajo GPU. Capturar trazas de frame-time del juego/compositor cuando disponibles y con igual overhead; definir «tirón» antes de medir (p. ej. frame-time >50 ms o >2 veces presupuesto). No calcular 1% low de medias de segundos.
- Registrar PSS/RSS y heap/GC cuando disponibles, errores/crashes/logcat, estado térmico Android y temperatura de sensores válidos con unidad/fuente. Estado thermal HAL y grados no son equivalentes. Separar arranque, calentamiento, tramo estable y deterioro sostenido.
- F3+2: nueva serie para A y B, mismo recorrido y tiempo; no mezclar con la serie sin profiler. Adjuntar capturas/exportaciones originales y registrar su sobrecarga.

## Decisión y recuperación

Comparar distribución/variabilidad entre repeticiones, no elegir solo el mejor FPS. Sin registros compatibles no emitir porcentaje de mejora. Rechazar como mejora cualquier cambio con fallos visuales, controles desalineados, crashes, pérdida de datos, calor sostenido peor o stutter mayor no justificado. Desactivar experiments/DRS y volver a Original; usar backup de datos y APK anterior si es necesario. Mantener la release funcional mientras no se complete aceptación física.
