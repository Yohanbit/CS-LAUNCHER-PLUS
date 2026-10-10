# Resolución dinámica: corrección de menús

- Al liberar el cursor, el inventario y los menús recuperan la resolución manual mediante el evento existente de GLFW, sin esperar al muestreo de un segundo.
- Al volver al mundo se recupera su última resolución dinámica; se descartan las muestras de menús y se reinicia el enfriamiento entre cambios.
- Los cambios usan pasos de 2,5 puntos porcentuales, cinco muestras bajas, diez muestras sanas para recuperar y cinco segundos entre ajustes.
- El porcentaje mínimo es absoluto respecto a la pantalla: base 60% y mínimo 30% no significa 18%.
- La lectura del estado del cursor entre hilos usa visibilidad explícita (`volatile`).

## Alcance y pruebas

El launcher sigue escalando todo el framebuffer. El HUD mientras se juega no tiene un render target independiente; no se afirma que su tamaño sea constante. El ajuste restaura el tamaño manual de inventarios y menús, no fuerza el tamaño de una pantalla al 100% ni cambia `guiScale` en `options.txt`.

Prueba en dispositivo: seleccionar 60% manual, mínimo 30%, activar DRS, esperar una reducción, abrir/cerrar inventario varias veces y comprobar tamaño, toque y retorno al mundo. Comparar la misma escena con DRS apagada/encendida, primero sin gráficos F3+2 y después con ellos. Registrar tiempos y temperatura; el coste de F3+2 y la presentación FIFO no están medidos aquí.

Las pruebas de política se ejecutan con `sh tools/test-dynamic-resolution.sh`. Compilar no demuestra aumento de FPS ni estabilidad física en Mali-G76.
