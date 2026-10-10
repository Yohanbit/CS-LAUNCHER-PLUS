# Entrega — Unamed launcher 1.4.3 de prueba

## Resultado

[Prerelease publicada](https://github.com/Yohanbit/Unamed-launcher/releases/tag/vulkan11-1.4.3-38035591430), con marca **Pre-release** comprobada públicamente. [APK](https://github.com/Yohanbit/Unamed-launcher/releases/download/vulkan11-1.4.3-38035591430/Unamed.launcher.apk): 107339792 bytes, aproximadamente 102.4 MiB.

[CI](https://github.com/Yohanbit/Unamed-launcher/actions/runs/38035591430): **completed/success**, intento 1; build y publish aprobados. Commit compilado: `f7d53619d0d89b1ef5f54e2e4560285fdbd8a7ab`. [PR incremental #5](https://github.com/Yohanbit/Unamed-launcher/pull/5), rama `fix/vulkan11-resume-validation`, basada exactamente en `44c3861c0f9844317aff01866188843064fb144d`. El commit de implementación conserva todos los cambios de ronda 3 y añade seis archivos modificados/nuevos de validación y documentación. No se fusionó ni sobrescribió main/base, ni se retiraron releases.

## Validación realizada

- Las 11 suites `tools/test-*.sh` pasaron en local y CI. La nueva política de publicación tiene 13 tests; runtime ronda3 aporta 286 checks.
- Suites nativas de puentes/loaders pasaron también con Clang local.
- CI compiló medición, puentes/contextos y loader Vulkan de producción con NDK para ARM32 y ARM64 en API21, sin dependencias atómicas auxiliares inesperadas.
- Compilación Android completa aprobada. Gradle `testDebugUnitTest` informó **NO-SOURCE**: no constituye una suite adicional de pruebas Android.
- Firma v1/v2 válida, paquete `com.cslaucher.plus.debug`, versión `1.4.3-vulkan11-test`, versionCode `10010020`, minSDK21 y targetSDK34.
- ARM32/ARM64, MobileGlues, LWJGL y SDL requeridos comprobados. ELF y dependencias de MobileGlues inspeccionados.
- Paquete/certificado/minSDK coinciden con la APK1.4.2 identificada; versionCode aumenta desde `10010018`. Checksum de la referencia verificado.
- APK pública descargada: checksum coincide con CI y el asset público; integridad ZIP y ambos ABI confirmados.

SHA-256 APK:
`917609fb4b8695324610937ccae97640f8d620b279fa1c9b69f86d1f66368666`

Certificado SHA-256:
`d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`

## Correcciones de esta continuación

Continuidad de actualización obligatoria; referencia 1.4.2 fijada por tag/checksum; APK subida solo después de inspección satisfactoria. Se añaden fixtures de parsing/firma/pipeline y se corrige la allowlist API21 del renderer. Los cambios runtime/Vulkan de ronda3 se conservan, no se añaden drivers externos ni requisitos Vulkan1.2/1.3.

## Límites y decisiones conservadas

Es una APK **debug de prueba**, con el certificado debug existente, no una firma privada de producción. Se conservó para continuidad; una futura distribución de producción necesita gestionar su propia clave de firma.

Sin pruebas físicas de instalación/migración, Minecraft/mods, Sodium, drivers Mali, FPS o temperatura. La inspección no certifica todos los símbolos de todas las bibliotecas en Android21. Vulkan1.1 declarado no garantiza extensiones ni soporte Zink. MobileGlues sigue usando OpenGL ES; no se incorpora un nuevo backend ni código propietario. ARM32/ARM64 y minSDK21 se mantienen. Seguimientos no incluidos: topes/defaults RAM basados en hardware, recuperación EGL/OSMesa y watcher Vulkan→GL sin nuevo pause/resume.

Los informes `apk-inspection.txt`, `tests.txt`, `commit.txt` y `previous-apk.json` acompañan a la prerelease. Notas de versión impersonales: `RESUME-RELEASE-NOTES.md`.
