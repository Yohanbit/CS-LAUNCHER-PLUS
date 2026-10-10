# Reanudación — validación de publicación

## Punto de partida comprobado

PR #4 abierto y borrador; rama `fix/vulkan11-native-round3`, HEAD `44c3861c0f9844317aff01866188843064fb144d`. Base `fix/mali-runtime-round2`, revisión `f9845d76b032b48ec095cff4693c0be0d6c8cb85`. Comparación GitHub: un commit adelante, cero atrás, 41 archivos modificados. Los checkpoints citados en informes previos son históricos, no nuevos HEAD.

Run `38034314333`: completed/cancelled, intento 1. Suites host/NDK aprobadas en ese intento; compilación APK cancelada, inspección y subida APK omitidas, publicación cancelada sin pasos ejecutados. Página pública: un artefacto `Unamed-launcher-verification`, 7.71 KB, digest `58a30daaf7ea5a7321c4e4ab91437c49f7c9e847c8aa7cf498f9a0786193f5e5`. No artefacto APK. Lista pública de releases: 1.4, 1.4.1 y 1.4.2; tag esperado `vulkan11-1.4.3-38034314333` devuelve 404.

## Rama independiente y alcance

Continuación en `fix/vulkan11-resume-validation`, basada en el HEAD comprobado de ronda 3. Se conservan los cambios anteriores, ARM32/ARM64 y minSDK 21. No se fusiona ni sobrescribe main/base, no se retiran releases.

Se corrige un hueco concreto: la inspección anterior imprimía `NOT MATCHED` pero retornaba éxito ante incompatibilidad de actualización. Ahora CI requiere la APK 1.4.2 identificada y su checksum documentado, bloquea incompatibilidad y sube el artefacto APK solo después de la inspección satisfactoria. Las pruebas nuevas usan fixtures y no sustituyen apksigner/aapt de CI.

## Límites conservados

Vulkan 1.1 declarado no certifica extensiones, features ni backend Zink. No se añade un renderer o driver nuevo, ni se modifica código propietario de Minecraft. JVM/JIT no equivale a emulación; Native Image no se presenta como sustituto compatible con mods dinámicos. Se conserva la investigación pública y de licencias en `ROUND3-PUBLIC-RESEARCH.md`.

No hay pruebas físicas de instalación/migración, Mali, Sodium, Minecraft/mods, FPS o temperatura. La coincidencia de metadata de actualización no prueba migración en dispositivo. La corrección no aborda los topes/defaults de RAM aún basados en capacidad de hardware, ni amplía recuperación EGL/OSMesa; no se silencian overrides JVM explícitos.

La aprobación final de la APK y publicación solo puede registrarse tras el nuevo run exitoso y comprobación de sus artefactos. No se hereda una aprobación de APK del run cancelado.

La revisión focal de publicación aprobó los bloqueos de continuidad. Se ampliaron fixtures para parsing de identidad, fallos de firma/CLI/pipeline, ABI extra y biblioteca vacía. La allowlist del renderer ya no considera Vulkan (API24) ni AAudio (API26) como bibliotecas del sistema disponibles en API21. La inspección ELF/dependencias sigue focalizada en MobileGlues; no certifica disponibilidad de todos los símbolos Android ni instalación en API21.
