# Unamed launcher 1.4.1 — prueba técnica

- Contador acumulativo de swaps con ventanas independientes para HUD, controles y diagnóstico; reinicios y pausas invalidan ventanas anteriores.
- Tiempos detallados de swap habilitados solo durante diagnóstico; resultados etiquetados como espera CPU, no como tiempo GPU ni presentación física.
- Resolución dinámica con límites de FPS conocidos, histéresis y recuperación estable; transiciones de menús conservan la escala del mundo.
- Configuración de renderer, cachés, opciones Java y presupuesto de memoria filtrada según backend y runtime seleccionados.
- Lanzamientos de instaladores sin renderer conservados; rutas Gallium sin puente nativo completo excluidas de selección/fallback en lugar de aceptar bibliotecas no utilizables.
- Selección conservadora de hilos, restauración de prioridades y recuperación térmica; ADPF sin reporte fiable de duración CPU queda desactivado.
- Pruebas de lógica y concurrencia incorporadas al flujo Android; inspección de firma, ARM32/ARM64, bibliotecas y requisitos de actualización adjunta.

APK de diagnóstico firmada para pruebas. La inspección adjunta determina si el certificado coincide con la APK anterior; no se presupone actualización compatible. Rendimiento sostenido, instalación, menús, controles y comparación A/B en dispositivos Mali pendientes de prueba física. No se modifica ni retira la release anterior.
