# Ronda 3 — watcher de primera submission: lifecycle y cobertura Vulkan

## Resultado

Corrección acotada implementada en `/tmp/unamed-work`, rama `fix/vulkan11-native-round3`. **Sin commits ni pushes.** No se tocaron runtime JNI/C, superficies, DRS, políticas ni otras clases de performance. La ampliación del wrapper Java `FpsCounter` fue autorizada por el padre; el worker nativo implementa su entry point independientemente.

### Defecto demostrado antes del cambio

`MainActivity` publicaba `mFirstFrameWatcher` en `onCreate`; ante un total no positivo volvía a publicarlo cada 250 ms. `onPause` y `onStop` no lo cancelaban; únicamente `onDestroy` retiraba el callback. Consecuencias demostrables por el flujo del código:

* Trabajo periódico y lecturas JNI incluso con la actividad pausada.
* Una submission tardía podía ejecutar `playing()` después de que `onPause` hubiese comunicado `paused()`.
* Un renderer nativo Vulkan, cuyo total es deliberadamente `-1`, nunca concluía ese bucle.
* `-1` mezclaba cobertura no disponible durante startup GL y Vulkan explícitamente no instrumentado. Cortar todo `-1` habría roto el warmup GL tardío; el nombre Zink tampoco identifica una ventana Vulkan `NO_API`.

## Cambios concretos

### 1. Watcher estrictamente foreground y una sola ejecución vigente

Nuevo helper de producción `launch/FirstSubmissionWatch.java`, sin dependencias Android salvo el wrapper de contador. MainActivity usa su mismo `Tools.MAIN_HANDLER`; no se crean hilos ni handlers adicionales.

* No publica ni lee JNI en `onCreate`.
* `onResume` restaura loading/playing y después arma un único callback.
* `onPause` y `onStop` cancelan antes de comunicar pausa o limpiar otros recursos.
* `onDestroy` hace release permanente antes del resto de destrucción.
* Una generación por run invalida callbacks retenidos antes de pause/resume/destroy. Los callbacks obsoletos no leen JNI, notifican visibilidad ni republican trabajo.
* Finishing/destroyed, doble resume, doble pausa y posts rechazados se manejan sin multiplicar loops ni quedar falsamente activos.

### 2. Cobertura explícita, sin inventar frames Vulkan

`FpsCounter.getCounterCoverage()` expone:

| Valor | Significado | Acción del watcher |
|---|---|---|
| `COVERAGE_UNREADY = 0` | Startup/fallo/no linkage conocido | Reintento foreground con backoff de 250 a 2000 ms, sin fecha límite arbitraria |
| `COVERAGE_COUNTED = 1` | GL/OSMesa instrumentado | Lectura del acumulado y warmup cada 250 ms hasta submission positiva |
| `COVERAGE_NATIVE_VULKAN_UNSUPPORTED = 2` | Ventana Vulkan nativa sin boundary instrumentado | Termina ese foreground run; no lee totales GL antiguos ni certifica visibilidad/playing |

El entry point esperado es `Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage`, de retorno `jint`. No lo implementé ni modifiqué yo: pertenece al worker nativo. Se conserva `getTotalPresents()` con su contrato `-1` para cobertura desconocida. Un entry point nuevo ausente en librerías antiguas, o un enum inválido, devuelve UNREADY sin certificar cobertura.

Tras leer un acumulado se vuelve a comprobar la cobertura: una transición observada a `NO_API` o UNREADY descarta la lectura anterior. No hay heurísticas por GPU, renderer string o nombre Zink.

### 3. Warmup y semántica del contador preservados

No hay deadline que descarte el inicio GL lento. Cuando GL pasa a estar instrumentado se recupera la cadencia de 250 ms. Totales `0` o `-1`, incluso después de un reset, no notifican una submission; una posterior lectura positiva sí.

El baseline de este **latch** es el acumulado del proceso, no una ventana FPS: si la primera submission ya ocurrió antes del callback o mientras estaba pausado, el resume GL no exige una segunda swap artificial para liberar el callback. No se resetea el contador nativo ni se consumen las ventanas de otros lectores.

Una detección positiva es one-shot; futuros resumes restauran playing sin nuevas lecturas del watcher. El nombre antiguo `mFirstGameFramePresented` y comentarios de MainActivity/FpsCounter se sustituyeron por términos de submission: **no es prueba de GPU completion ni presentación física**.

No se añadieron gates a `runCraft`, `setSurfaceReadyListener`, `LaunchStageView`, `BootLogOverlay`, toques del usuario o DRS. El callback GL existente sigue llamando `playing()` y `mBootLog.onGameVisible()`. Vulkan no recibe ese callback ficticio; los mecanismos existentes independientes del overlay permanecen intactos.

## Pruebas ejecutadas

Suite nueva ejecutable: `tools/test-round3-watcher.sh`.

Compila Java de producción con `javac --release 8`: wrapper FpsCounter, FrameCounterWindow y helper FirstSubmissionWatch. Extrae **sin reescribir** el initializer y las instrucciones de lifecycle relevantes de MainActivity a un caller con dobles inertes. Además valida por fuente que create no arme el watcher, que pause/stop/destroy lo detengan primero y que no aparezcan callsites nuevos ocultos. Usa un JNI spy compilado con `-std=c11 -O2 -Wall -Wextra -Werror`, no el driver real.

Casos verificados:

* JNI sin enlazar y librería antigua con total disponible pero entry point de cobertura ausente.
* Enum nativo inválido tratado como UNREADY.
* Create sin callbacks/lecturas; doble resume sin duplicación.
* 1000 reintentos UNREADY sin perder warmup; backoff limitado a 2000 ms.
* GL tardío, cero stall, total temporalmente desconocido, reset a cero, posterior contador positivo.
* Callback GL de estado/overlay one-shot, y resume posterior sin JNI adicional.
* Vulkan explícito: cero lecturas del acumulado y cero callback de primera submission; tampoco reabre por doble resume o callback obsoleto.
* Cambio Vulkan→GL: permanece terminado en ese run; se recupera al siguiente foreground sin exigir una swap adicional.
* Cambio GL→Vulkan o GL→UNREADY durante la lectura: no atribuye el total anterior al nuevo estado.
* Finishing/destroyed y posts iniciales/periódicos rechazados.
* **100 secuencias completas create→resume→pause→stop→resume→destroy**, reteniendo callbacks de generaciones anteriores: cero JNI y cero republishes en background/después de destroy.

Resultado: **PASS con GCC/cc y Clang**. También pasaron las regresiones existentes de `test-measurement.sh` y `test-overhead-lifecycle.sh`. `git diff --check`: PASS.

Logs exactos:

* `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round3-watcher-host.txt`
* `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round3-watcher-clang.txt`
* `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round3-measurement.txt`
* `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round3-overhead.txt`

Invocaciones reproducibles:

```sh
cd /tmp/unamed-work
tools/test-round3-watcher.sh
CC=clang tools/test-round3-watcher.sh
sh tools/test-measurement.sh
sh tools/test-overhead-lifecycle.sh
git diff --check
```

Requisitos de la suite nueva: Linux, Python3, JDK17/21 con headers JNI (`JAVA_HOME` opcional, se detecta desde javac) y compilador C. No necesita Android SDK/NDK. Añadir la suite al workflow corresponde al padre.

## Archivos de mi ownership cambiados/nuevos

* `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java`
* `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/FpsCounter.java`
* `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/launch/FirstSubmissionWatch.java` — nuevo
* `tools/Round3WatcherLifecycleTest.java` — nuevo
* `tools/round3_watcher_harness.py` — nuevo
* `tools/round3_watcher_jni.c` — nuevo; solo fixture host
* `tools/test-round3-watcher.sh` — nuevo y ejecutable

Los otros cambios concurrentes del working tree son de los demás workers/padre; no se alteraron.

## Límites y decisiones explícitas

* Después de coverage UNSUPPORTED, una transición posterior de hint dentro del **mismo** foreground run no se detecta por este watcher, porque precisamente dejó de sondear. Se recupera en el siguiente pause/resume real. Esta decisión fue coordinada con el padre y está cubierta por regresión. No existe evento Java de cambio de hint dentro del scope autorizado.
* La relectura evita transiciones visibles durante la lectura, pero no pretende ser un snapshot transaccional ni distinguir un cambio ABA completo de ventana entre dos lecturas. El contador es acumulativo por proceso.
* UNREADY puede seguir reintentando en foreground a 2000 ms mientras un startup GL siga pendiente; parar ese estado indefinidamente sin una señal terminal rompería el warmup tardío. Nunca sigue en background.
* Tests con dobles/JNI spy, no Activity Android completa ni dispositivo real. La compilación APK y la validación de la implementación JNI real quedan al pipeline/worker nativo.
* La regresión measurement saltó la compilación NDK completa local por `ANDROID_NDK_HOME` ausente; pasaron los probes freestanding ARM32/ARM64, que no equivalen a build NDK/APK.
* Ninguna medición física, ganancia FPS o mejora GPU afirmada. Se eliminó trabajo periódico verificable donde carecía de utilidad.

## Incidencias / feedback

No fallos de tests ni blockers pendientes en mi ownership. Avisos normales de JDK21 sobre target/source8 obsoletos. El único comando exploratorio inicial intentó buscar una carpeta `lwjgl*` inexistente; no afectó cambios ni suites.

La instrucción inicial solo permitía MainActivity/helper/tests; se amplió explícitamente a FpsCounter Java para poder distinguir Vulkan sin recortar el warmup GL. No se necesitó cargar herramientas externas. Contexto recomendado para futuras rondas: MainActivity, FpsCounter, FirstSubmissionWatch y esta suite; revisar por separado los watchers propios de BootLogOverlay/LaunchStageView, no afirmar que esta corrección los modifica.
