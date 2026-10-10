# Integración y verificación — ronda 2

## Revisiones y protección de ramas

- Base de código: `13f0d24f38494a3a745739c7f4fed601e88b6fc0`, conservando la primera ronda sobre `cs-1.4-full-fps-extreme`.
- Revisión remota observada antes de publicar: `cs-1.4-full-fps-extreme` = `72f4b0f45fc9f44dbb771d6b6b30a38d8825eb25`; `main` = `910ff101781b9ad5279cefad5932e2ca5e40f0fb`.
- Rama independiente: `fix/mali-runtime-round2`. PR incremental sobre la rama de la primera ronda. No se autoriza merge ni se elimina ninguna release.

## Evidencia y cambios

Los tres informes adjuntos detallan por defecto encontrado su evidencia, modificación, pruebas y límites:

- [Java/memoria/lanzamiento](ROUND2-RUNTIME-VALIDATION.md).
- [Sobrecarga y ciclo de vida](ROUND2-LIFECYCLE-VALIDATION.md).
- [Puentes gráficos nativos](ROUND2-NATIVE-VALIDATION.md).

No se añadió código propietario de Minecraft ni código externo nuevo; modificaciones propias bajo la licencia existente. Se conservan dependencias, fuentes y avisos anteriores. La familia Mali es el objetivo general, no una garantía de equivalencia entre GPU/drivers.

## Incidencias de integración resueltas antes de publicar

1. Una primera ejecución del nuevo fixture OSMesa no encontraba `KHR/khrplatform.h`. El fixture host ahora aporta tipos mínimos temporales; las compilaciones GCC/Clang posteriores pasan. El NDK usa sus tipos de plataforma reales.
2. La revisión independiente encontró que exigir éxito OpenGL dentro de `glfwInit()` impedía llegar al hint NO_API de Vulkan cuando GL fallaba. Se separó la inicialización de plataforma y la del contexto GL. La nueva regresión ejecuta **init → hint NO_API → create** con backend GL fallido y sin renderer; las ventanas NO_API no requieren GL. Cambiar a contexto GL con backend inválido devuelve fallo, sin dispatch nulo. Contextos GL normales/compartidos inicializan el bridge una sola vez.
3. Las cifras preliminares del informe de lifecycle (58 nuevas pruebas runtime) quedan sustituidas por **71**; su fallo host inicial de headers queda sustituido por la ejecución integrada completa posterior.

## Pruebas ejecutadas antes de CI

Comando reproducible desde la raíz: `for t in tools/test-*.sh; do bash "$t" || exit; done` y `git diff --check`.

Resultado integrado: aprobado. Resolución 298 +102 assertions; pacing/options 37; runtime 184 +71; selección nativa 7; ventanas/pausas/concurrencia de métricas, 400.000 submissions nativos; lifecycle con 100 carreras y nueve fallos de inicio inyectados; loaders EGL/OSMesa y setup de contexto con fallos inyectados. Las pruebas de lifecycle/puentes nuevos también pasan con GCC y Clang.

Java 21 host acepta la sintaxis G1/logging generada y rechaza flags desconocidos; esto no certifica los runtimes Android. Los dobles de Android y loaders validan lógica de producción, no un dispositivo real. Sin SDK/NDK local, la compilación ARM32/64 local se omite explícitamente. CI exige NDK y compila las unidades nativas y la APK completa; su resultado se registra por separado.

## Conservado y pendiente

- glFinish OSMesa y sincronizaciones necesarias; sin backend nuevo ni driver experimental.
- Diagnóstico, DRS y experimentos JVM/prioridad opt-in; ADPF sin activar hasta medir duración CPU correcta.
- Sin porcentajes de FPS, GPU-time inventado, pruebas Sodium ni mediciones térmicas de un teléfono.
- Pendiente físico: instalación/migración, runtime Android/ABI real, contexto y fallback gráfico, Minecraft/mods, menús/controles y A/B reproducible con temperatura/memoria/F3+2 separado.
- Pendientes de futuras rondas: recuperación amplia de superficies EGL; binding runtime OSMesa; watcher de primer frame de MainActivity que no pausa explícitamente; helpers de arquitectura basados en capacidades del dispositivo, no en el ABI efectivo del proceso.
