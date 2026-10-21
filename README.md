# Sailer

Navegador Android personal, ligero y centrado en la privacidad, con descargador de vídeo, imágenes y audio.

## Qué hace (versión 1.3)

**Privacidad**
- **Incógnito siempre**: al salir se borran cookies, caché, historial, formularios y almacenamiento de las webs. Si la app se cierra de golpe, se borra al volver a abrirla.
- **Solo HTTPS**: las direcciones `http://` pasan a `https://`; las conexiones sin cifrar o con certificados falsos se bloquean.
- **Sin capturas**: no se pueden hacer capturas ni grabaciones, y en "apps recientes" la vista previa sale en negro.
- **Bloqueador** de anuncios, rastreadores y redirecciones de publicidad: ~250.000 dominios (StevenBlack, HaGeZi PRO++, EasyList, EasyList Adult, EasyPrivacy).
- **Bloqueo cosmético**: oculta los huecos y banners de anuncios dentro de las páginas (reglas de EasyList y EasyList Adult).
- **Pop-ups**: solo se abre una pestaña nueva si tocas un enlace que lleva justo ahí; la publicidad que se abre al tocar en cualquier sitio se descarta.
- Si una web no funciona bien, puedes desactivar el bloqueador solo en esa web desde el menú ⋮.
- **Bloqueo con PIN o huella** al abrir la app y cada vez que vuelves a ella. El PIN se guarda atado al chip de seguridad del móvil (no se puede adivinar copiando los datos) y tras 5 fallos hay que esperar cada vez más.
- **DNS cifrado** (DNS over HTTPS) en las páginas y en las descargas, y guía para activar el DNS privado de Android para todo el móvil.
- **Favoritos privados**, cifrados y protegidos con tu PIN.
- **Disimulo opcional**: icono y nombre de «Calculadora» (Ajustes).
- Sin ubicación, cámara ni micrófono para las webs, sin copias de seguridad en la nube y sin enviar datos a Google.

**Descargas**
- Botón de descarga (flecha ⬇️ arriba): muestra la portada de la página y cada vídeo/audio con su **calidad** (720p, 1080p…), **tamaño**, **duración** y formato, marcando el que probablemente es el vídeo principal y avisando de las vistas previas cortas. Los vídeos en streaming **HLS (.m3u8)** aparecen con cada calidad disponible.
- Vídeos **DASH (.mpd)** sin DRM y vídeos con el **audio en una pista aparte**: se bajan las dos pistas y se unen en un solo archivo.
- **Pausar y reanudar** descargas (desde la carpeta segura o la notificación). Si se corta internet, se reintentan solas y siguen donde iban.
- Mantén pulsada una **imagen** o un **enlace** para descargarlo.
- Los vídeos con **DRM** (Netflix, etc.) no se pueden descargar.
- Todo se guarda en la **carpeta segura**: cifrada (AES-256-GCM, que además detecta si alguien ha tocado los archivos) y oculta de la galería. Desde ahí puedes ver, renombrar, borrar o exportar.
- **Copia de seguridad cifrada** con contraseña (Ajustes): guarda la carpeta segura y los favoritos en un archivo para reinstalar o cambiar de móvil.

## Cómo instalarlo en el móvil

**Opción fácil (después de aprobar el PR):** en GitHub entra en **Releases** (columna derecha de la página del repositorio), abre la más reciente y descarga `Sailer-x.x.x.apk`.

**Opción desde Actions:**
1. Abre la pestaña **Actions**, entra en la ejecución más reciente de **Compilar APK** con el círculo verde ✅.
2. En **Artifacts** descarga **Sailer-apk** (un .zip). Dentro está `Sailer-x.x.x.apk`.

Después, abre el `.apk` en el móvil. Android pedirá permiso para "instalar apps desconocidas" desde esa app: acéptalo y pulsa **Instalar**. Las versiones nuevas se instalan encima sin perder la carpeta segura.

## Limitaciones conocidas

- Los vídeos protegidos con DRM y las emisiones en directo no se pueden descargar.
- Si el móvil no sabe unir un formato concreto de audio y vídeo, se guardan como dos archivos.
- Al unir audio y vídeo, el resultado existe sin cifrar unos segundos dentro del almacenamiento privado de la app antes de cifrarse.
- Una descarga en pausa se pierde si Android cierra la app por completo.
- El DNS cifrado de las páginas necesita un WebView reciente (Android System WebView actualizado desde Play Store).
- Si no se configura una clave privada en los *secrets* de GitHub (`SAILER_KEYSTORE_BASE64`, `SAILER_KEYSTORE_PASSWORD`, `SAILER_KEY_ALIAS`), el APK se firma con la clave de depuración incluida en el repositorio. Cambiar de clave obliga a desinstalar: haz antes una copia de seguridad.

## Estructura del código

```
app/src/main/java/com/sailer/browser/
├── MainActivity.kt        Pantalla principal (barra, menú, pestañas, descargas)
├── SailerApp.kt           Arranque: limpieza, bloqueador, bloqueo
├── adblock/               Bloqueador de anuncios y rastreadores
├── download/              Cola de descargas, HLS, DNS cifrado, servicio en segundo plano
├── lock/                  Bloqueo con PIN/huella y base segura de las pantallas
├── media/                 Detección de vídeo/audio en las páginas
├── privacy/               Borrado de los datos de navegación
├── settings/              Ajustes
├── tabs/                  Pestañas
├── vault/                 Carpeta segura cifrada y visor
└── web/                   Configuración privada de WebView
```

Las listas de bloqueo se generan con `python3 tools/build_blocklists.py` a partir de [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT), [HaGeZi](https://github.com/hagezi/dns-blocklists) (GPL-3.0) y [EasyList](https://easylist.to) (GPL-3.0 / CC BY-SA 3.0). Vuelve a ejecutarlo de vez en cuando para actualizarlas.
