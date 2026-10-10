# Correcciones verificables de rendimiento — rama de prueba

Base comprobada: `cs-1.4-full-fps-extreme`, commit `72f4b0f45fc9f44dbb771d6b6b30a38d8825eb25`.

Rama independiente: `fix/verified-performance-mali-g76`. No fusionar en `main` ni en la rama base sin autorización.

## Ejecución

1. Corregir medición: contador acumulativo, ventanas independientes, instrumentación detallada opcional, cobertura explícita y ciclo de vida.
2. Estabilizar límites y resolución: Unlimited/manual/original, VSync por backend, solicitudes de frecuencia, objetivos alcanzables y transiciones.
3. Revisar estabilidad: fallback compatible, variables del renderer real, prioridades y restauración de hilos, ADPF, temperatura y opciones Java/memoria.
4. Integrar controles reales y pruebas de lógica/concurrencia/errores; ejecutar también las pruebas existentes.
5. Compilar ARM32/ARM64; inspeccionar APK, firma y continuidad frente a un artefacto anterior identificado.
6. Publicar sólo una prerelease nueva con notas técnicas de esta actualización. No borrar ni retirar ninguna release existente.

El registro final separará evidencia, cambio o conservación justificada, prueba, resultado y limitaciones para los 25 puntos solicitados. Las pruebas de lógica y compilación no equivalen a ejecutar Minecraft ni medir un Mali-G76 físico. La comparación A/B en teléfono se entregará como procedimiento reproducible, no como resultados inventados.

Estado inicial: pruebas existentes ejecutadas sin cambios (`test-extreme-options.sh`: 7 aserciones; `test-dynamic-resolution.sh`: 106 aserciones). Entorno local OpenJDK 21; compilación Android prevista en JDK 17.
