# Librerías de terceros servidas localmente

Se sirven desde el propio servidor (y no desde un CDN) para que la política CSP solo permita
scripts del mismo origen y un CDN comprometido no pueda inyectar código en el juego.

| Librería | Versión | Origen | Integridad del paquete (npm `dist.integrity`) |
|---|---|---|---|
| Leaflet | 1.9.4 | https://registry.npmjs.org/leaflet/-/leaflet-1.9.4.tgz | sha512-nxS1ynzJOmOlHp+iL3FyWqK89GtNL8U8rvlMOsQdTTssxZwCXh8N2NB3GDQOL+YR3XnWyZAxwQixURb+FA74PA== |
| stomp.js (stompjs) | 2.3.3 | https://registry.npmjs.org/stompjs/-/stompjs-2.3.3.tgz | sha512-5l/Ogz0DTFW7TrpHF0LAETGqM/so8UxNJvYZjJKqcX31EVprSQgnGkO80tZctPC/lFBDUrSFiTG3xd0R27XAIA== |

Para actualizar: descargar el .tgz, verificar la integridad contra `npm view <paquete>@<versión> dist.integrity`
y copiar `dist/leaflet.js`, `dist/leaflet.css`, `dist/images/` o `lib/stomp.min.js`.
