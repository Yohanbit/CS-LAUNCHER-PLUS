# Unamed launcher

Launcher de Minecraft Java para Android. Fork de CS Launcher Plus basado en PojavLauncher.

## Descarga

[Descargar la APK actual en Releases](https://github.com/Yohanbit/CS-LAUNCHER-PLUS/releases)

Archivo: `Unamed.launcher.apk`. Compilación de prueba para ARM32 y ARM64.

## Cambios técnicos de esta edición

- MobileGlues 2.0.0 y bibliotecas Android de LWJGL integrados.
- Modos de FPS Unlimited, Manual y Original. Unlimited elimina el límite del launcher; no elimina los límites de Minecraft, del backend ni de la pantalla.
- Resolución dinámica opcional, con mínimo configurable y ajustes graduales.
- Inventario y menús recuperan la resolución manual; al volver al mundo se recupera la última resolución dinámica.
- Ajustes de rendimiento agrupados y opciones de calidad reversibles.
- Inicio sin servidor promocional ni tutoriales integrados.

La resolución dinámica escala el framebuffer completo: el HUD del mundo todavía no tiene una resolución independiente. No se garantiza una cifra de FPS; el rendimiento depende del dispositivo, los mods, el mundo y la temperatura.

## Código e instalación

El código de la edición actual está en [cs-1.4-full-fps-extreme](https://github.com/Yohanbit/CS-LAUNCHER-PLUS/tree/cs-1.4-full-fps-extreme). `main` conserva el código base; esta actualización solo cambia su documentación.

Instala la APK como actualización de esta edición sin borrar datos. Haz una copia de seguridad de tus mundos. Los requisitos de Minecraft y Java dependen de la versión utilizada.

## Créditos y licencia

- **Craft Studio / craftstudioteam y ROHIT_45**: CS Launcher Plus, proyecto original de este fork.
- **PojavLauncher Team y colaboradores**: base del launcher.
- **MobileGL-Dev**: MobileGlues.
- **Yohanbit**: fork y edición Unamed launcher.

Licencia del proyecto: [GNU GPL v3](LICENSE). Se conservan los avisos originales y las licencias propias de las bibliotecas. [Créditos y componentes de esta edición](https://github.com/Yohanbit/CS-LAUNCHER-PLUS/blob/cs-1.4-full-fps-extreme/CREDITS.md).

No afiliado a Mojang ni Microsoft.
