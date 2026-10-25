# Little latino cat

Plugin independiente y experimental de Cloudstream para https://www.peelink2.com/inicio.

## Estado

Versión inicial de desarrollo. No se ha compilado ni probado en Android al escribir estos archivos. No se promete reproducción. No modifica ningún otro proyecto.

Incluye búsqueda, catálogo paginado, lectura de fichas y un primer intento de resolución mediante extractores de Cloudstream. Los selectores HTML son provisionales y deben verificarse con las respuestas reales del sitio. Solo admite películas. No identifica todavía idiomas de cada servidor ni añade categorías. Los enlaces con dominios no reconocidos se omiten y los reproductores cargados por JavaScript no están implementados.

## Compilación

GitHub Actions intenta generar un archivo `.cs3` y lo adjunta como artefacto si la compilación termina correctamente. Aún no hay `repo.json` ni distribución automática instalable. Revisar el resultado antes de instalar.

La configuración de compilación se basa en la del proyecto público recloudstream/TestPlugins. Las dependencias SNAPSHOT pueden cambiar.

## Uso

Usar únicamente con contenido al que se tenga derecho de acceso. No se evaden DRM, CAPTCHA, autenticación, pagos ni acortadores publicitarios. No contiene claves ni cookies.
