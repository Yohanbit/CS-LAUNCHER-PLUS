# Ronda 3 — Vulkan 1.1, arquitectura efectiva y rutas nativas

## Base y restricciones

Código publicado de partida: rama `fix/mali-runtime-round2`, revisión remota `f9845d76b032b48ec095cff4693c0be0d6c8cb85`; APK 1.4.2 compilada desde `70ef402cf049047f1015de3d901d82b1964687e9`. Checkpoint local equivalente en contenido: `1d630a86d4346622f17c533edf19ad3f83cbdf56`. Rama nueva: `fix/vulkan11-native-round3`. No fusionar main/base ni retirar releases.

Dato declarado por el usuario: teléfono Mali con soporte máximo Vulkan 1.1. No se conocen extensiones Vulkan efectivas, versión Android ni ABI efectivo del proceso; no se inventan. Permiso para ARM64-only o subir mínimo Android si existe necesidad técnica demostrada, no como mejora especulativa.

## Plan

1. Verificar cargas nativas Vulkan y contratos completos de init/hint/create; corregir fallos demostrables sin agregar requisitos superiores a Vulkan1.1.
2. Corregir decisiones basadas en booleanos/capacidades nominales: versión Vulkan declarada no equivale a extensiones ni capacidad de un backend. Revisar bitness efectivo del proceso y avisos de memoria/runtime.
3. Controlar watcher del primer frame por lifecycle/cobertura; no retener polling sin instrumentación.
4. Investigar requisitos y licencias de backends públicos y viabilidad de trasladar trabajo a nativo. No modificar/republicar código propietario de Minecraft ni confundir JVM/JIT con emulación de hardware.
5. Integrar pruebas host de producción y errores, compilación NDK ARM32/64 y APK; inspeccionar firma/paquete/versionCode frente a prerelease anterior. Publicar otra prerelease con notas solo técnicas.

Solo se activan correcciones seguras validadas. No se reemplaza un requisito Vulkan mediante variable de entorno ni se afirma velocidad/compatibilidad Mali/Sodium sin pruebas físicas. Los cambios de ABI/Android se justifican por separado; si no son necesarios se conserva compatibilidad.
