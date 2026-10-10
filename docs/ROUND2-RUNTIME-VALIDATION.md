# Ronda 2 — Java, memoria y lanzamiento

## Estado y alcance

Implementación lista para integrar en `/tmp/unamed-work`, rama `fix/mali-runtime-round2`, base `13f0d24f38494a3a745739c7f4fed601e88b6fc0`. Sin commits ni push. No se modifica la entrega APK aprobada de la primera ronda.

Tres grupos de correcciones basadas en bugs observables, no ajustes de rendimiento especulativos. Se modificaron exclusivamente `Tools.java`, `utils/JREUtils.java`, `multirt/Runtime.java`, `multirt/MultiRTUtils.java`, `performance/RuntimeJvmArguments.java`, `performance/RuntimeMemoryBudget.java`, `performance/JvmPolicy.java` y pruebas propias. Nuevos: `multirt/RuntimeLibraryLoader.java` y `tools/RuntimeLaunchRegressionTest.java`. `tools/test-runtime-safety.sh` ejecuta automáticamente las pruebas nuevas.

## 1. Headroom cero no equivale a «sin aviso»

**Bug comprobado en la base:** el diálogo requería `advisoryHeap > 0` para considerar el presupuesto conservador. Con RAM total 4096 MB, disponible 700 MB y heap elegido 512 MB, el presupuesto es 0, pero 512 no supera los 700 MB brutos: no aparecía aviso. Además, `Math.max(256, advisoryHeap)` convertía un presupuesto desconocido/agotado en una aparente recomendación válida de 256 MB. El cálculo también recibía la memoria física ya limitada al hueco de direcciones y restaba de nuevo la reserva Android/native/GPU a ese hueco.

**Corrección:** `HeapAdvice` distingue medición conocida de desconocida; disponible 0 es agotamiento medido, negativo es desconocido. El techo de direcciones 0 también es agotamiento medido, -1 desconocido. Con headroom conocido insuficiente se avisa incluso si el heap cabe en la RAM bruta. No se ofrece una sugerencia inferior a 256 MB ni se infla una sugerencia 0/128 a 256. En ese caso se ofrece cancelar o lanzar con la selección explícita, informando del riesgo. Si existe presupuesto >=256 MB, «Use … MB» sigue siendo una elección expresa que se persiste. El cálculo recibe la RAM física sin pre-limitar; el hueco de direcciones se aplica como techo independiente. El log distingue ahora desconocido de insuficiente.

**Invariantes:** el Xmx seleccionado en ajustes no se cambia de forma automática; la reserva es advisory, no medición de GPU ni garantía contra LMK. El tratamiento preexistente de Xmx/Xms custom como opciones controladas por el ajuste global se conserva.

**Pruebas concretas:** presupuesto cero con heap dentro de RAM bruta, disponible cero, lectura desconocida, techo cero, techo 1024 sin doble reserva, presupuesto mínimo insuficiente y selección exactamente igual al recomendado.

## 2. Claves JVM exactas y validación real de flags en el host

**Bugs comprobados en la base:** `getJavaArgs()` comparaba `startsWith(strippedArg)`: una propiedad `-Djava.home.extra=…` podía impedir la adición de `-Djava.home`, y una propiedad extendida de idioma podía suprimir la propiedad base. El purgado de propiedades también borraba claves que sólo compartían prefijo. Más adelante se añadían siempre allocator, biblioteca SPVC y methods-injector aunque el usuario hubiera declarado esas mismas claves/ruta de agente, provocando duplicados y sobrescritura por orden.

**Corrección:** `addDefault()` y `removeKey()` comparten identidad exacta de propiedades/opciones, con Xmx/Xms normalizados y booleanos XX +/- como una misma clave. Las propiedades extendidas sobreviven. Los defaults de allocator/SPVC y el agente methods-injector respetan la clave/ruta explícita; agentes con rutas distintas no se conflan. Las opciones de seguridad incompatibles purgadas por nombre escalar se comparan completas, no por prefijo.

**Flags:** la decisión enabled/default-off se hace explícita en el helper puro y se usa desde `JvmPolicy`, manteniendo salida vacía para política nula, experimento desactivado, plan ergonómico o versión desconocida. No se añadió `IgnoreUnrecognizedVMOptions`, flags nuevos, GC predeterminado ni knobs. Las opciones del usuario no se validan mediante una whitelist incompleta ni se silencian.

**Pruebas reales del host:** procesos Java 21 iniciados sin flags experimentales y con ambas variantes G1 generadas; sintaxis de logging GC generada aceptada y archivo producido; `-XX:DefinitelyUnknownRound2Flag=1` rechazado con salida no cero y `Unrecognized VM option`. Pruebas puras adicionales verifican experimento desactivado para versiones 0/8/11/17/21/25, opt-in con versión 0, preservación de claves y ausencia de duplicados automáticos.

**Limitación importante:** esto valida HotSpot del host, no cada runtime Android. No se ejecuta un JVM auxiliar en Android antes de arrancar el juego ni se presume que una versión mayor identifique al proveedor/VM. Los experimentos siguen desactivados por defecto y deben validarse específicamente en un runtime Android antes de atribuirles utilidad. No se reescribió el parser histórico de texto JVM ni se normalizaron decisiones duplicadas explícitas del usuario o argumentos completos de modloaders/manifests.

## 3. Runtime corrupto/fallido: error temprano y limpieza pre-JVM

**Bugs comprobados en la base:** el split por punto seguido de `Integer.parseInt()` lanzaba excepción para una versión válida sin punto como `25-ea`; metadatos malformados podían abortar el escaneo. Una selección null podía llegar a construir rutas/read incorrectos. La reinstalación de un runtime conservaba metadatos viejos en caché. `DIRNAME_HOME_JRE` podía conservar `lib/<arch>` de un lanzamiento anterior al cambiar a un runtime moderno. Fallos de `dlopen(libjli)` y de ambos intentos `libjvm` se ignoraban. El finally del servidor local de skins sólo envolvía `VMLauncher`, dejando el bridge vivo si fallaba antes el renderer, entorno o preparación JVM.

**Corrección:** parser puro del release, claves exactas, CRLF y versiones legacy/update/build/EA; datos malformados producen runtime inválido de versión 0, no NumberFormatException. Las selecciones null/vacías fallan descriptivamente y no se leen como un runtime normal. Se invalida caché tras reinstalar y al seleccionar para lanzamiento. El directorio de bibliotecas vuelve primero a `lib`. `RuntimeLibraryLoader.require()` exige éxito de las bibliotecas de entrada imprescindibles, conserva el fallback existente de libjvm y muestra ruta/instalación/ABI/dependencias al fallar. Las demás bibliotecas AWT opcionales conservan su tratamiento anterior. Finalmente, la limpieza del servidor local abarca preparación y ejecución, manteniendo los caminos de crash-screen y salida limpia.

**Pruebas:** versiones 1.8.0_402-b06, 17.0.14+7, 21, 25-ea y 25-ea+13; null/vacío/overflow/malformado; claves release falsas; arch faltante; release CRLF. Cargador inyectable: orden exacto de fallback, parada tras primer éxito, fallo obligatorio de libjli y de ambas rutas libjvm. Las pruebas del cargador son de lógica con dobles, no dlopen Android.

## Invariantes gráficos preservados

No se cambió la selección/fallback de renderer. AWT con `LOCAL_RENDERER == null` sigue retornando null sin probing. Freedreno/Panfrost siguen excluidos por falta de wiring validado. Se mantiene preparar entorno antes de constructores dlopen, reset entre candidatos, reconciliación al renderer efectivo y precedencia de `custom_env.txt`. No se añadieron hacks Adreno/Turnip sobre Mali ni suposiciones de uniformidad entre miembros de la familia Mali. No se afirma compatibilidad de Sodium ni mejoras de FPS.

## Resultado de pruebas y evidencia

Ejecución con OpenJDK 21.0.12.1. El script runtime compila con `javac --release 8`.

- `sh tools/test-runtime-safety.sh`: **184 checks existentes PASS + 71 checks nuevos PASS**.
- `sh tools/test-extreme-options.sh`: **37 PASS**.
- `sh tools/test-dynamic-resolution.sh`: **298 PASS + 102 regresiones PASS**.
- `sh tools/test-measurement.sh`: ventanas, lifecycle, concurrencia y atomic probe **PASS**, según log compartido de integración. Se ejecutó sin modificar esos componentes; corresponden también al trabajo de otros agentes.
- `git diff --check`: **PASS**.
- Log completo: `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/round2-runtime-tests.log`.

Los warnings del compilador son de compatibilidad Java8 obsoleta en JDK21 y bootstrap-classpath en los scripts antiguos; no hubo fallos de pruebas.

## Límites y siguiente validación

No hay Android SDK/NDK configurado localmente ni dispositivo disponible: no se compilaron todas las clases Android ni el APK de esta ronda en este subagente. CI/parent debe compilar ARM32/ARM64 y revisar el APK integrado. La prueba NDK de measurement indicó SKIP; los probes freestanding sí pasaron, pero no sustituyen un NDK build.

En dispositivo deben verificarse: diálogo con poco headroom y cancelar/override; runtime real 8/17/21/25 y archivo GC opt-in; runtime corrupto/ABI erróneo y error temprano; cleanup offline si renderer/env/JVM falla; AWT null; cambio de renderer y preservación de custom_env. El aviso de memoria aún usa los helpers de arquitectura del proyecto (capacidad del dispositivo), no una nueva comprobación ELF/proceso; no se certifica desde el host la selección de runtimes de otro ABI. Una carga exitosa de bibliotecas no garantiza símbolos lazy, creación de contexto o soporte de mods.

## Licencias

Código nuevo propio dentro del repositorio, bajo su licencia existente **GPLv3** (`LICENSE`). No se copió código externo, no se introdujeron paquetes/dependencias ni se descargaron/proporcionaron runtimes o binarios nuevos. Las obligaciones existentes de redistribución del APK/runtimes y notices permanecen; la publicación y conservación de fuentes/licencias corresponde al padre/CI.
