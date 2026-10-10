# Investigación pública — Android ARM64, familia Mali y Vulkan 1.1

**Acceso a fuentes: 10 de octubre de 2026.** Investigación documental; no se probó un teléfono físico ni se verificaron modelo de GPU, versión/driver Vulkan, extensiones, features o GLES. “Vulkan 1.1” es el dato comunicado, no un volcado de capacidades.

## Conclusiones técnicas

### MobileGlues 2.0.0: backend, requisito y licencia

- El README oficial de **MobileGlues-release** identifica 2.0.0 como la versión más reciente y lo describe como implementación de OpenGL sobre **OpenGL ES del host**: recomienda ES 3.2 y fija ES 3.0 como mínimo declarado. No es un renderer Vulkan propio y el dato Vulkan 1.1 no acredita por sí solo el requisito GLES.
- El README declara LGPL-2.1 para MobileGlues y el plugin. El repositorio del núcleo publica un `LICENSE` LGPL-2.1. Para integrar/distribuir código se deben cumplir las obligaciones aplicables de la licencia (avisos, fuente correspondiente de modificaciones y derechos de reemplazo/relink según la forma de distribución); comprobar por separado licencias y avisos de dependencias.
- **Inventario confirmado del APK 1.4.2:** contiene MobileGlues; no contiene Mesa, Zink, `libOSMesa`, `libEGL_mesa` ni `glxshim`. Por ello Zink no es una ruta disponible en ese APK tal como está inventariado. No se descargaron ni recomendaron drivers nuevos.

### Zink oficial de Mesa y Vulkan 1.1

- Zink es un driver Gallium que emite llamadas Vulkan y puede ofrecer **OpenGL de escritorio**, no OpenGL ES. La documentación oficial hace explícito que el nivel OpenGL depende tanto de la implementación de Zink como de las capacidades del driver Vulkan; publica perfiles `VP_ZINK_requirements.json` para comparar capacidades del dispositivo y contempla `zink_check_requirements`.
- Vulkan 1.1 **no es prueba universal de compatibilidad ni motivo de rechazo universal**. En la tabla actual de requisitos de Mesa, Zink indica OpenGL 2.1 mínimo sobre Vulkan 1.0, sujeto a features y extensiones requeridas; incluye `logicOp`, `fillModeNonSolid`, `alphaToOne`, `shaderClipDistance`, varias extensiones KHR/EXT, y `VK_EXT_scalar_block_layout` o Vulkan 1.2 con el feature `scalarBlockLayout`. El requisito de OpenGL 3.0 agrega `independentBlend`, `VK_EXT_transform_feedback` y `VK_EXT_conditional_rendering`; versiones GL más altas suman progresivamente features, extensiones, formatos y límites. Para GL 4.3, por ejemplo, requiere `robustBufferAccess` y capacidades de formatos adicionales. Así, con Vulkan 1.1 hay que verificar el perfil/nivel OpenGL que precisa la aplicación y todas las capacidades concretas del driver; la versión API aislada no basta.
- En la cadena analizada, Zink requeriría una integración de Mesa y del contexto OpenGL de escritorio compatible con el launcher. El inventario indicado no muestra esos componentes; por tanto Zink es una alternativa de ingeniería/investigación, no una capacidad presente ni una mejora integrable en el estado actual del APK. La documentación citada no prueba que una compilación Android concreta de Mesa/Zink funcione en este dispositivo o con su driver Mali.

### ANGLE: investigación posible, no integración prioritaria confirmada

- ANGLE documenta backend Vulkan en Android, compatibilidad GLES y una guía de desarrollo para Android ARM64. Su ejemplo de renderer Vulkan 1.1 no es mínimo formal ni certificación para la familia Mali. Las capabilities del driver Vulkan y la ruta de despliegue real siguen siendo determinantes.
- La documentación de ANGLE describe restricciones en la selección del driver del sistema y en sus rutas de prueba (por ejemplo, app depurable o root para el paquete de pruebas); no equivale a que cualquier APK de usuario pueda activar un backend. La evidencia disponible no valida una integración de ANGLE en este launcher o dispositivo. No se debe priorizar como mejora ya viable ni inferir que resolvería el requisito GLES de MobileGlues sin probar toda la cadena y revisar licencias/empaquetado.

### ART, HotSpot y “emulación”

- ART es el runtime Android de código DEX y combina interpretación, JIT y AOT/profile-guided compilation; no es un “emulador puro”. HotSpot es otra JVM, con interpretación y compilación JIT de código caliente.
- En una app launcher, su código Java/Kotlin normalmente pasa por ART; Minecraft Java puede correr dentro de un JRE/JVM ARM64 suministrado por el launcher. Se debe identificar ese runtime. Ajustar ART no implica automáticamente acelerar código del juego ejecutado por otra JVM; “Java en Android” no significa que ART traduzca directamente el juego de PC.

### GraalVM Native Image, JNI y mods

- Native Image genera binarios desde análisis estático bajo un supuesto de mundo cerrado. Funciones dinámicas como reflexión requieren metadatos durante build; JNI está soportado, pero las clases y elementos JNI que se necesiten deben conocerse/registrarse para construir la imagen.
- Compilar de una vez Minecraft Java, loader y mods arbitrarios es una mala coincidencia con ese modelo: clases descargables, classloading, reflexión/JNI dinámica y bibliotecas nativas (incluidas las asociadas a LWJGL) tendrían que resolverse anticipadamente. No se concluye que sea imposible para un conjunto cerrado, solo que no existe evidencia consultada de reemplazo drop-in del JRE del launcher para Minecraft modificado en Android ARM64.
- Para evaluar la idea primero hay que identificar runtime Java, ABI, LWJGL y ámbito de mods soportados. Native Image acotada para componentes propios/cerrados es conceptualmente más realista que compilar el juego completo; no se recomienda portar el juego propietario ni se estima porcentaje de mejora.

## Estado / límites

1. Hecho documentado: MobileGlues 2.0.0 requiere OpenGL ES 3.0 como mínimo declarado y tiene licencia LGPL-2.1 según sus fuentes públicas; no usa Vulkan directamente.
2. Hecho del inventario aportado: APK 1.4.2 solo incluye MobileGlues; no incluye los componentes Mesa/Zink indicados.
3. Posible investigación futura, no integración ya respaldada: comprobar GLES existente; consultar capabilities Vulkan del driver de fábrica; valorar ANGLE solo tras verificar el empaquetado permitido y la cadena efectiva. Para Zink, cotejar perfiles/requirements oficiales contra capabilities reales y primero resolver integración Mesa no presente en el APK.
4. No se descargaron nuevos drivers, no se modificó código, no se probó un teléfono físico y no se hicieron afirmaciones de FPS o soporte para una GPU exacta.

## Fuentes públicas (10)

1. MobileGlues-release, README (versión, GLES y requisito, licencia declarada): https://github.com/MobileGL-Dev/MobileGlues-release
2. MobileGlues, `LICENSE` del núcleo (LGPL-2.1): https://github.com/MobileGL-Dev/MobileGlues/blob/main/LICENSE
3. Mesa, documentación oficial de Zink (backend, perfiles, requisitos/features/extensions por nivel GL): https://docs.mesa3d.org/drivers/zink.html
4. ANGLE, matriz de backends y soporte GLES/plataformas: https://github.com/google/angle
5. ANGLE, guía Android (arm64, ruta de driver, restricciones y ejemplo Vulkan 1.1): https://github.com/google/angle/blob/main/doc/DevSetupAndroid.md
6. Android Developers, soporte Vulkan en motores nativos (versión y consulta de capacidades): https://developer.android.com/games/develop/vulkan/native-engine-support
7. AOSP, configuración y modelo JIT/AOT/interpretación de ART: https://source.android.com/docs/core/runtime/configure
8. OpenJDK, descripción del runtime HotSpot/JIT: https://openjdk.org/groups/hotspot/docs/RuntimeOverview.html
9. GraalVM, compatibilidad Native Image, supuesto cerrado y reflexión: https://www.graalvm.org/latest/reference-manual/native-image/metadata/Compatibility/
10. GraalVM, JNI en Native Image y metadatos: https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JNI/

## Desconocido

- Modelo/implementación exactos de GPU Mali, sistema operativo, driver de fábrica y disponibilidad/versión de OpenGL ES en el teléfono.
- Extensiones, features, límites y perfiles Vulkan reales del driver; cuál nivel OpenGL de Zink sería suficiente para el uso pretendido.
- Ruta de ANGLE considerada por el launcher, restricciones de distribución y compatibilidad de su backend Vulkan con capabilities concretas Mali.
- Runtime JVM/JRE y versión LWJGL usados por Minecraft; loader/mods dinámicos que deben seguir admitiéndose.
- El inventario de APK se declara confirmado para 1.4.2, pero no se deduce de ello el contenido de futuras versiones.
