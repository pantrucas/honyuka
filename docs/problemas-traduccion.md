# Problemas vistos al leer Blue Exorcist 169–171 (nov 2026)

Lista de las páginas que el lector marcó, la causa encontrada en los datos (`translations/…/*.json`)
y qué cambio de la app lo cubre. Sirve como lista de pruebas para las próximas versiones.
Los capítulos ya hechos **no** se re-hicieron; esto es para las próximas traducciones.

## Causas

| # | Causa | Arreglo (versión) |
|---|---|---|
| A | El OCR (ML Kit) lee solo algunas líneas de un globo; la app borraba solo esas, el resto del inglés quedaba a la vista. | 0.5.4: `BubbleFinder` encuentra el globo blanco y se repinta entero (`paintBubble`). |
| B | El OCR parte un globo en trozos; cada trozo se traducía y dibujaba aparte (frases cortadas). | 0.5.4: `mergeSameBubble` junta trozos del mismo globo al leer (Gemini ve la frase entera) y al dibujar. |
| C | El texto en español se ubicaba solo en el recuadro de las líneas leídas: quedaba chico y apretado. | 0.5.4: `Bubble.growInside` usa el ancho del globo. |
| D | Bloques de 1 letra («I») se descartaban siempre. | 0.5.4: se guardan hasta juntar por globo; solo se descartan si quedan sueltos. |
| E | Letras grandes sobre el dibujo (sin globo): el recuadro no cubría todas las líneas. | 0.5.4: `LeftoverCheck` relee la página ya traducida con el OCR del teléfono y agranda el recuadro / agrega lo que falte. |
| F | OCR con letras mal leídas («REDUC ON UCERS», «ORDEKS») → español sin sentido. | Parcial: B le da a Gemini la frase completa junto a la imagen. |
| G | Gemini tardaba más de 60 s en responder y la petición se cortaba (prueba de «Redo all» del 171). | 0.5.4: espera de 180 s; el aviso final con el error queda visible. |

## Páginas marcadas

| Capítulo · página | Lo que se veía | Causa |
|---|---|---|
| 169 · 18 | Quedó «BATTLE BETWEEN» en inglés (el OCR leyó «IN YET ANOTHER» y «EXORCSTS.»). | A |
| 169 · 20 | Quedó «TO THE» («AT LEAST ACCORDING / TO THE / REPORTS.» leído en dos trozos). | A, B |
| 170 · 8 | Inglés a la vista y español sin sentido: «FEVER AND PAIN REDUCERS» leído como «REDUC ON UCERS», frase repartida en 5 trozos. | A, B, F |
| 170 · 10 | Quedó la «I» de «I FEEL SO HEAVY!» y el texto salió muy chico. | C, D |
| 170 · 28 | No se borró parte del inglés («…BUT IT MAY NOT LAST LONG…» leído solo «LONG…»). | A |
| 170 · 35 | Globo partido en «I..», «DON'T KNOW», «YET!» → trozos sueltos. | B |
| 171 · 7, 11, 16, 23 | Globos con varias piezas / líneas no leídas. | A, B |
| 171 · 25 | Quedó «AGENCY, I'M PRETTY CERTAIN.»; columna de globos unidos partida en «¿EH? ¿A… / ¡¿AL MUNDO?! / ¡ESO ES DEMASIADO!». | A, B |
| 171 · 32 | Letras grandes sobre el dibujo («I… FORGOT…!!»): quedaron la «I» y los «!!» alrededor del recuadro blanco. | E |
| 171 · 33 | «ORDEKS» y frase en trozos. | B, F |
| 171 · 35 | «…TO THOSE PEOPLE.» sobre el dibujo: quedó «TO» encima del recuadro. | E |

## Cómo comprobar

1. Hacer una copia de prueba del manga (otro nombre) con los capítulos y sus `.json`, y apretar «Re-render».
2. Mirar las páginas de arriba: no debe quedar inglés y el texto debe usar el ancho del globo.
3. Mirar también páginas que ya estaban bien (p. ej. 168 · 3, 10, 20, 30) para comprobar que nada empeoró: globos abiertos, texto sobre el dibujo, efectos de sonido conservados.
