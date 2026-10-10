# Runtime fixes — entrega local consolidada

Implementados R01/R02/R03/R05/R07/R09 y parte segura de R08; R04 solo diagnóstico honesto. Sin commit/push ni cambios de build.gradle, versión, workflow, lifecycle/nativewatch o UI ajena. Los archivos finales se entregan también bajo nombres cortos en esta carpeta. `runtime-changed-files.json` contiene `{repoPath,localPath,sourcePath,sha256}` por archivo; los repoPaths **no** llevan prefijo `source/`.

## Fixes y regresiones integradas

- **R01:** techo/default RAM por ABI del proceso; Xmx explícito final intacto. Tests reales extraídos de Tools/LauncherPreferences, hardware64/proceso32.
- **R02:** parser conectado JREUtils → RuntimeArgumentTokenizer. Conserva orden, quoted spaces, escapes y múltiples `=`; rechaza quote sin cerrar. Tests del método real + keys reservadas/heap final, no helper desconectado.
- **R03:** `experimentalModWorkarounds=false`, misma key del checkbox Experimental de UI (`source-ui/LauncherPreferenceFragment.java:967`). Tools consume preferencia + filename match; ningún nombre de mod certifica compatibilidad. JVM explícito gana. Mixin conserva ajustes del usuario y heredados; ownership/backup permite deshacer únicamente líneas propias intactas. Configs faltantes no se crean. Los tres workarounds ya estaban en base; v1 retiró solo el añadido incondicional de `sodium.checks.issue2561=false` en JREUtils, no el condicional Tools. No se eliminan arbitrariamente las opciones heredadas viables.
- **R05:** selección inicial automática no elige Mesa/Zink cuando faltan alternativas no Mesa. Error claro exige selección manual; la selección explícita continúa viable.
- **R07:** extractor de producción tar.xz con contención de cada ruta, symlink relativo y hardlink root-relative; comprueba enlaces anteriores, incluidos dangling; rechaza absolutos/traversal/special devices/targets ausentes o cíclicos. `Os.symlink(target,linkPath)`/`Os.link(target,linkPath)` conectados en MultiRTUtils. Ranking/import/install/autopick de Tools/NewJREUtil filtra ABI del proceso; guard final JREUtils conservado.
- **R08:** sin LWJGL en ruta no-game no se emite `null/libfreetype.so`; juego valida Freetype y core liblwjgl. No fuerza módulos opcionales de JVM. Presencia de archivo no garantiza símbolos ni contexto.
- **R09:** lector options read-only usa última ocurrencia; no modifica archivo para diagnosticar.
- **R04:** dlopen correcto no prueba EGL/backend/contexto. Se añade diagnóstico; no se implementa fallback nativo transaccional. Una inicialización nativa fallida exige error/reintento, no afirmación de fallback probado.
- **R06:** no se inventan alternativas ausentes. APK inspeccionado MobileGlues-only conocido en ambas ABI ARM; import/download de runtime permanece accesible cuando disponible. Sin prueba de descarga/ejecución real ni stack Zink completo.

## Resultado de pruebas

`tools/test-consolidation-runtime.sh`: **exit 0**, **620 checks PASS** (55 consolidación + 24 extracción tar.xz/filesystem real + 184 safety + 71 launch regression + 286 round3). Log: `audit/runtime-consolidation-test-log.txt`; exit: `audit/runtime-consolidation-exit.txt`. Último run completo se hizo sobre copia byte-idéntica en `/tmp/runtime-final-source` para evitar latencia del filesystem cloud. Sin build Gradle/Android completo.

Primera extracción test detectó aceptación de LF_CHR; rechazo de block/character/FIFO añadido y regresión pasó. Primera suite final directamente sobre cloud agotó timeout; se repitió completa sobre copia local. Ninguna descarga HTTP se realizó desde sandbox.

Host: Java21, commons-compress1.27.1/xz1.9. Proyecto: commons-compress1.24.0/xz1.8; test script exige versiones exactas en CI mediante caché Gradle o RUNTIME_TAR_TEST_CP y falla si faltan/no coinciden. **Ese run exacto de dependencias sigue pendiente**; Android Os no fue ejecutado. Es obligatorio validar build/dependencias exactas antes de promoción, no presentar el test host como equivalente Android.

## Histórico y límites que debe preservar el coordinador

Histórico fuente cerrado mediante 315 snapshots verificados por coordinador, comparación completa de 15 archivos runtime principales y arrastre solo entre transiciones sin modificación acreditada. Evidencia función/version/hashes/conexiones en `audit/runtime-matrix.json`; narrativa en `audit/runtime-report.md`. Architecture existía antes de v2; aparecer en manifest v2 no es incorporación al repo.

Extracción usa directorio privado sin adversario concurrente: no openat/TOCTOU transaccional, cuotas de archive ni rollback de instalación parcial. Backup/ownership mixin protege edits discernibles; no puede distinguir autoría de una reescritura posterior idéntica a una línea propia. No borrar ajustes heredados no atribuibles.

Validación de artefacto debe usar release normal GitHub **1.5.0**, paquete/firma existentes; el APK debug examinado es distinto. Sin dispositivo, mods reales, shaders, FPS o prueba física. No simular drivers, Vulkan capabilities ni fallback de contexto. No activar ADPF sin medición CPU genuina. Merge obligatorio de UI checkbox y estos cambios runtime, luego build/test exacto y validación de artefacto.
