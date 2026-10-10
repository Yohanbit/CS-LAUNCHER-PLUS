# Entrega técnica — Unamed launcher 1.4.2

## Enlaces y artefacto

- [Código, PR incremental #3](https://github.com/Yohanbit/Unamed-launcher/pull/3), rama independiente `fix/mali-runtime-round2`.
- [Prerelease de prueba](https://github.com/Yohanbit/Unamed-launcher/releases/tag/runtime-1.4.2-38032930792), marca Pre-release comprobada públicamente.
- [APK descargable](https://github.com/Yohanbit/Unamed-launcher/releases/download/runtime-1.4.2-38032930792/Unamed.launcher.apk): 102.4 MiB, ARM32/ARM64.
- [Compilación, pruebas NDK y publicación aprobadas](https://github.com/Yohanbit/Unamed-launcher/actions/runs/38032930792). Código compilado: `70ef402cf049047f1015de3d901d82b1964687e9`.
- Identidad: `package: name='com.cslaucher.plus.debug' versionCode='10010018' versionName='1.4.2-runtime-test' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'`.
- SHA-256 de la APK descargada: `14c3e0256ea2508800c2f79b7e251caef1950e774eebe9eead2f97307649c6a6`, coincide con inspección CI; integridad ZIP comprobada.
- Certificado SHA-256: `d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`; firma debug existente válida, sin crear una firma de producción nueva.
- Frente a la APK anterior de la primera prerelease: `Update prerequisites: same_package= True same_certificate= True higher_version_code= True`; SHA-256 anterior `129d2a8b30fbb36ca2a8c3b18e9c932bfb06971352a384869c344d993a1e3d99`. Paquete/certificado coinciden y versionCode aumenta. **No equivale a prueba física de instalación o migración.**

## Resultado de validación

Todas las suites host integradas pasaron; pruebas nativas nuevas y de lifecycle aprobadas con GCC y Clang. CI además compiló las unidades de puentes/loaders/contextos de producción con NDK para armv7a API21 y aarch64 API21 y la APK Android completa. Se verificaron inclusión MobileGlues por ABI, natives Android LWJGL/SDL, requisitos de instalación y firma. Los artefactos tests.txt/apk-inspection.txt/commit.txt/previous-apk.json están adjuntos a la prerelease. La tarea Gradle de unit tests no aporta tests Android adicionales si reporta NO-SOURCE.

La revisión detectó una regresión antes de publicar: init GL temprano bloqueaba Vulkan antes del hint NO_API. Se corrigió con inicialización GL diferida; la secuencia completa init → hint → create se revalidó independientemente con backend GL fallido y renderer nulo. No quedó ese bloqueador abierto en el código compilado.

No se fusionó en main ni en la rama base. PR #3 está apilado sobre la rama del PR #2 para mostrar solo esta ronda y conservar la anterior. Las releases previas no se eliminaron.

## Pendientes físicos y alcance

No hay mediciones de un teléfono ni ganancias de FPS demostradas. Pendientes: instalar/actualizar conservando datos, lanzamiento Minecraft/mods/runtimes Android reales, contextos y drivers Mali, menús/controles, memoria/GC, temperatura sostenida y A/B bajo las mismas condiciones, F3+2 por separado. Se mantiene glFinish OSMesa, experimentos opt-in y ADPF desactivado. No se promete compatibilidad probada con Sodium ni con todos los miembros de la familia Mali.

Seguimiento técnico separado: recuperación de superficies EGL, binding runtime OSMesa, pausa del watcher de primer frame y selección por ABI efectivo del proceso. No se modificó código propietario de Minecraft.
