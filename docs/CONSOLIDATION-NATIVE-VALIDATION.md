# Consolidación nativa N01–N04 — informe final

## Entrega y ámbito

Implementación local autorizada, sin commit/push. Aplicar **solo** los 16 archivos enumerados en `native-changed-files.json`: 9 productivos JNI y 7 de pruebas. El JSON contiene `repoPath` exacto, `localPath`, SHA-256 y resultados/logs. No se modificaron Java, MainActivity, performance collector/thermal, workflows ni build.gradle por este agente. La matriz lee el estado final disponible de MainActivity/FrameSampleCollector, que puede incorporar cambios de los agentes hermanos; no son archivos de esta entrega.

Artefactos en `/tasklet/threads/a_54z3cj9n4pgxxx0k7m53/work/audit/fixes-native/`:

- `native-fixes.md`: este informe.
- `native-changed-files.json`: mapping de aplicación y resultados de pruebas.
- `native-matrix.json`: conservación base/v1/v2/v3/final por archivo y función, conexión, pruebas y límites.
- `full-comparison.patch`: comparación completa histórica y final del ámbito; **no** aplicar como patch de esta entrega porque también contiene historia/cambios de otros ámbitos.

## Resultado por defecto

### N01 — FIXED_SOURCE, regresión host PASS

`gl_init`/`osm_init` ya no certifican cobertura por disponer únicamente de loader/display. `pojavCreateContext` publica cobertura solo después de crear un contexto. El fallo del primer contexto conserva UNREADY/cobertura desconocida; el fallo de un contexto compartido no invalida un principal creado correctamente. `pojavTerminate` revoca inmediatamente disponibilidad y cobertura, incluso si debe diferir la limpieza. Los lectores JNI conservan sus ABI y devuelven desconocido, no un cero FPS medido, cuando no hay cobertura.

La prueba compila `egl_bridge.c`, los backends reales y `frame_metrics.c`; inyecta fallo principal/shared, verifica readiness, FPS/total/snapshot, exclusión de pbuffer y revocación al terminar.

### N02 — PARTIAL_SAFE: teardown real conectado, no promesa de limpieza universal

Se sustituyó la terminación del PotatoBridge desconectado por registro de bundles reales y destructores `gl_destroy_context`/`osm_destroy_context`. `bridge_lifecycle_mutex` serializa creación/binding/teardown. `bound_threads` sigue el binding real observado antes/después de make-current; la limpieza no libera objetos que otro hilo mantiene bound. Swaps largos y `glFinish` no mantienen ese mutex: el binding registrado protege el bundle/backend frente a teardown.

Se balancean referencias de ventana activa/pendiente, y se preservan recursos para reintentar si fallan destroy-surface/destroy-context/terminate. La terminación completa es idempotente cuando los recursos realmente pueden destruirse. Los punteros NO_API reciben referencias de sesión independientes, retenidas hasta terminate; cambiar/liberar la publicación Java no invalida un puntero previamente devuelto.

**Límite obligatorio:** si OSMesa rechaza unbind, un worker sigue bound, abandona el hilo sin unbind, o un destructor falla persistentemente, el teardown queda diferido. No existe aquí un destructor TLS/expulsión de contextos ajenos ni garantía de reintento automático. El propietario debe unbind/terminar ordenadamente y volver a llamar terminate; algunos drivers OSMesa pueden no permitir limpiar la sesión antes del fin del proceso. No declarar “teardown completo sin leaks”. Los callers Vulkan deben destruir sus VkSurface/usos de ventanas antes de terminar GLFW.

### N03 — FIXED_SOURCE para publicación/ownership de ventanas

La UI solo publica la referencia obtenida por `ANativeWindow_fromSurface` bajo `window_mutex`. `pojav_acquire_window` y `pojav_take_window_update` adquieren una referencia **dentro del mismo mutex**, evitando acquire-after-free; una generación registra reemplazos, misma ventana y eventos NULL. Solo el hilo de render modifica las superficies del bundle y transfiere pending → active. Fallar la conversión JNI no revoca la ventana previa.

Se revisaron todos los accesos `pojavWindow` del árbol JNI disponible: quedan únicamente los accesos protegidos en publicación/snapshots. `gl_swap_surface` no consulta dimensiones/puntero global como falso test de ownership; conserva el delay heredado, pero libera exactamente sus propias referencias tras destruir EGL. OSMesa evita doble unlock/post después de un error: un post consume el lock una vez. `glFinish` se conserva.

La protección es mutex + referencias propias + consumo por render, **no** un atomic de puntero. Pruebas concurrentes de 1000 publicaciones con snapshots/EGL y 1000 publicaciones/swaps OSMesa, referencias misma ventana/NULL, errores y reintentos PASS. ASan/UBSan PASS no equivalen a ThreadSanitizer ni a certificación de ausencia de toda carrera del driver. No se certifican lifecycle Android físico ni APIs privadas del fabricante.

### N04 — FIXED_SOURCE de scan/mmap/ELF/fd; dispositivo pendiente

`find_branch_label` rechaza inputs inválidos, verifica mprotect y limita la lectura al resto de la página conocida; interpreta correctamente el desplazamiento ARM64 firmado. No escanea una página siguiente desprotegida. El cambio de protección usa rango alineado y no solicita memoria writable-executable.

`patch_elf_soname` verifica `MAP_FAILED`, tamaño/tipo del archivo, ELF64 little-endian AArch64 ET_DYN, tamaños/rangos de secciones, enlace a string table, entradas dinámicas y SONAME terminado/con espacio suficiente. Lee campos por memcpy para evitar acceso desalineado. Maneja lecturas cortas/EINTR, msync y munmap; toma prestados ambos fd, nunca los cierra inadvertidamente.

`linker_ns_dlopen_unique` valida rutas, crea temporal único con CLOEXEC y unlink temprano, cierra fd/mappings en fallos y registra explícitamente el fd raíz que mantiene el pathname `/proc/self/fd` necesario para retener el mismo loader. Un loader rechazado olvida ese root; un loader exitoso conserva root/dependencias de proceso. Hooks fallan cerrado ante funciones/inputs ausentes, y namespace lookup no utiliza extension-info parcialmente inicializada. Un namespace creado cuyo enlace obligatorio falla se marca irreversiblemente fallido: no se acumula uno nuevo por reintento, pero no hay ABI pública segura para destruir ese namespace.

Pruebas host de producción: MAP_FAILED, msync fallido, 12 ELF malformados, SONAME ausente/corto, ownership fd/mmap/root, rechazo del loader y scan contra página siguiente protegida/desplazamiento firmado. La rama completa `ADRENO_POSSIBLE` compila en host. **No prueba compatibilidad del linker privado Android, ELF real del fabricante ni namespaces en dispositivo.** El scanner fail-closed puede hacer fallback cuando el wrapper OEM no coincide.

## Conservación histórica y conexión final

Las refs permanecen base `72f4b0f45fc9f44dbb771d6b6b30a38d8825eb25`, v1 `4f17662f6be682db2a116bd651dcd041fa1029e3`, v2 `70ef402cf049047f1015de3d901d82b1964687e9`, v3 `f7d53619d0d89b1ef5f54e2e4560285fdbd8a7ab`. Se usaron contenidos completos verificados y carry-forward solo donde las listas completas prueban ausencia de cambios. Se añadieron seis baselines v3 heredados verificados contra SHA Git para helpers/headers no descargados inicialmente.

| Funciones conservadas | Origen/presencia | Implementación/conexión final y prueba |
|---|---|---|
| `pojavCreateContext`, `pojavTerminate` | base, v1, v2, v3 | Dispatch GLFW → registry/binding → destructores reales; consolidation-native N01/N02 |
| `gl_swap_surface`, make-current/swap EGL/OSMesa | base y tres versiones | Snapshots con ownership → consumo render → active/pending; consolidation-native N02/N03, graphics-round2 |
| `find_branch_label`, `patch_elf_soname`, `linker_ns_dlopen_unique` | base y tres versiones, heredadas sin cambios históricos | Namespace opcional → scan/ELF acotados → owning root; consolidation-native N04 |
| `maybe_load_vulkan` | base y tres versiones | Loader validado con root retenido; vulkan-loader-round3 |
| `acquire_vulkan_loader` | v3 | JNI/LWJGL consumers con lease propia, separado del root; vulkan-loader-round3 |
| Contador/métricas/watcher | entrada exacta por versión en matriz | Swap principal → contador acumulativo no destructivo → ventanas lectoras y watcher; measurement/overhead-lifecycle/round3-watcher |

Matriz: **30 archivos, 152 entradas función/firma, 149 presentes finales y 11 nuevas definiciones de consolidación**. Las tres entradas no presentes son refactors ya auditados: `FrameSampleCollector.sample` → `SamplingRun.run`, cambio de nombres de parámetros en `persist`, e inlining de `FrameMetrics.readNativeSnapshot` en `Run.run` con retry JNI. No son funcionalidades gráficas eliminadas. La extracción de definiciones es ayuda de revisión, no inventario de símbolos producido por linker.

No se activa ningún renderer sin bridge, no se elimina opción/driver opcional ni `glFinish`; se conserva el root Vulkan deliberado. No se cambia ABI JNI de watchers/lectores. No se añade requisito Vulkan 1.2/1.3: se conservan comprobaciones de exports de loader 1.0 presentes en 1.1; esto no certifica features/extensiones del dispositivo.

## Validación final reproducida

**PASS host:** consolidation-native; consolidation-native con ASan/UBSan; native-bridge; native-graphics-round2; vulkan-loader-round3; measurement; overhead-lifecycle; round3-watcher. Logs `audit/native-fixes-final-*.log`, incluidos en el JSON de entrega. Sin warnings del compilador en las dos ejecuciones de consolidation-native; las suites Java muestran únicamente avisos de source/target 8 obsoletos.

**PASS separado:** probes freestanding de atomics armv7a/aarch64 API21, sin helpers libatomic. **SKIP:** compilación Android NDK ARM32/64 de todas las unidades modificadas porque `ANDROID_NDK_HOME` no está configurado. El nuevo runner la ejecuta para API21 cuando hay NDK y permite exigirla con `NATIVE_REQUIRE_NDK=1`; en ARM64 compila también el branch opcional. El CI previo no sustituye una compilación de estos cambios finales.

**NO EJECUTADO:** Android físico, GPU, namespaces de fabricante, benchmarks, compositor/presentación, APK completa/NDK final. Antes de publicación conviene exigir el compile ARM32/64 de esta entrega y smoke tests de recreación/suspend-resume, EGL/OSMesa y driver opcional en dispositivos apropiados. No publicar estabilidad del dispositivo ni teardown completo como resultado de estos tests host.
