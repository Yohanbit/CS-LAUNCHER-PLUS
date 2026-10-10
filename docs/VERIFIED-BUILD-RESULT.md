# Resultado de compilación y entrega

- Código compilado: [`4f17662f6be682db2a116bd651dcd041fa1029e3`](https://github.com/Yohanbit/Unamed-launcher/commit/4f17662f6be682db2a116bd651dcd041fa1029e3).
- [Compilación Android y publicación aprobadas](https://github.com/Yohanbit/Unamed-launcher/actions/runs/38031765502).
- [Prerelease pública](https://github.com/Yohanbit/Unamed-launcher/releases/tag/verified-1.4.1-38031765502), marca Pre-release verificada en navegador.
- [APK de prueba](https://github.com/Yohanbit/Unamed-launcher/releases/download/verified-1.4.1-38031765502/Unamed.launcher.apk) — 102.4 MiB; ARM64 y ARM32, Android API21 mínimo/target34.
- Firma debug existente, v1 y v2 verificadas; certificado SHA-256: `d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`. No se generó firma de producción nueva.
- SHA-256 APK descargada: `129d2a8b30fbb36ca2a8c3b18e9c932bfb06971352a384869c344d993a1e3d99`; coincide con inspección CI, CRC ZIP válido.
- Identidad del artefacto: `package: name='com.cslaucher.plus.debug' versionCode='10010001' versionName='1.4.1-verified-test' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'`.
- Continuidad: `Update prerequisites: same_package= True same_certificate= True higher_version_code= True`. Se compara con la APK identificada de [release anterior](https://github.com/Yohanbit/Unamed-launcher/releases/tag/extreme-1.4-38025677201), SHA-256 `aec9048fb08176cebc2f1b8cbb608bcf10a746304a868da2e350d6238db3618b`. Mismo paquete/certificado y código superior: requisitos de actualización coincidentes, no prueba de instalación/migración física. La release anterior sigue publicada y Latest.

## Pruebas y correcciones de integración

Todos los scripts host integrados y comprobaciones ARM NDK pasaron en CI. DRS: 298 assertions +102 regresiones; pacing:37; runtime:184; selección nativa:7 casos de función producción con dobles. Measurement valida ventanas, 8 lectores concurrentes, lifecycle/default-off/fallback labels, 100 carreras pause/tick y C real con 4 escritores/4 lectores y 400.000 submits. La compilación completa Android ARM32/64 terminó con BUILD SUCCESSFUL. Gradle testDebugUnitTest reportó NO-SOURCE: no es evidencia de tests de app ejecutados.

La primera compilación falló al evaluar versionCode con «Value is null» (build.gradle:100); se corrigió a asignación de propiedad y la siguiente compilación pasó. Revisión de integración encontró y cerró tres fallos: instaladores sin GL, rutas Gallium incompletas y atribución de sesiones después de fallback. Cada cierre tiene regresión host; ejecución física no realizada.

## Alcance conservado y pendientes

El objetivo actualizado es familia Mali, no G76; no se aplican hacks Adreno/Turnip a Mali ni se integra Panfrost por una coincidencia de nombre GPU. Contadores miden envíos; tiempos de swap son espera CPU-wall, no GPU/presentación. Diagnóstico detallado y DRS son opt-in; cambios especulativos no se anuncian como mejora demostrada. ADPF sigue desactivado sin duración CPU auténtica. Se conserva glFinish OSMesa y no se implementa HUD independiente.

Pendientes físicos: instalación y migración conservando datos, arranque de Minecraft/Java/mods, contexto y fallback de renderer, menús/inventario/touch, VSync/refresco efectivo, temperatura/recuperación, memoria/GC de cada runtime y comparación A/B, F3+2 aparte. No hay porcentajes ni mediciones del teléfono. Código en PR borrador; sin merge en main/base ni retirada de release funcional.

Documentación: [25 puntos](VERIFIED-25-POINTS.md), [protocolo A/B](VERIFIED-AB-PROTOCOL.md). Adjuntos de prerelease: [apk-inspection.txt](https://github.com/Yohanbit/Unamed-launcher/releases/download/verified-1.4.1-38031765502/apk-inspection.txt), [commit.txt](https://github.com/Yohanbit/Unamed-launcher/releases/download/verified-1.4.1-38031765502/commit.txt), [tests.txt](https://github.com/Yohanbit/Unamed-launcher/releases/download/verified-1.4.1-38031765502/tests.txt).
