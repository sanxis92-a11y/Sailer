# Sailer

Navegador Android personal, ligero y centrado en la privacidad, con descargador de vídeo, imágenes y audio.

## Qué hace (versión 1.0)

**Privacidad**
- **Incógnito siempre**: al salir se borran cookies, caché, historial, formularios y almacenamiento de las webs. Si la app se cierra de golpe, se borra al volver a abrirla.
- **Solo HTTPS**: las direcciones `http://` pasan a `https://`; las conexiones sin cifrar o con certificados falsos se bloquean.
- **Sin capturas**: no se pueden hacer capturas ni grabaciones, y en "apps recientes" la vista previa sale en negro.
- **Bloqueador** de anuncios, rastreadores, pop-ups y redirecciones de publicidad (lista de ~70.000 dominios).
- **Bloqueo con PIN o huella** al abrir la app y cada vez que vuelves a ella.
- **DNS cifrado** (DNS over HTTPS) en las descargas, y guía para activar el DNS privado de Android para todo el móvil.
- Sin ubicación, cámara ni micrófono para las webs, sin copias de seguridad en la nube y sin enviar datos a Google.

**Descargas**
- Botón de descarga (flecha ⬇️ arriba): lista los vídeos y audios de la página, incluidos los vídeos en streaming **HLS (.m3u8)**. Elige la mejor calidad automáticamente.
- Mantén pulsada una **imagen** o un **enlace** para descargarlo.
- Los vídeos con **DRM** (Netflix, etc.) no se pueden descargar.
- Todo se guarda en la **carpeta segura**: cifrada (AES-256) y oculta de la galería. Desde ahí puedes ver, renombrar, borrar o exportar.

## Cómo instalarlo en el móvil

**Opción fácil (después de aprobar el PR):** en GitHub entra en **Releases** (columna derecha de la página del repositorio), abre la más reciente y descarga `Sailer-x.x.x.apk`.

**Opción desde Actions:**
1. Abre la pestaña **Actions**, entra en la ejecución más reciente de **Compilar APK** con el círculo verde ✅.
2. En **Artifacts** descarga **Sailer-apk** (un .zip). Dentro está `Sailer-x.x.x.apk`.

Después, abre el `.apk` en el móvil. Android pedirá permiso para "instalar apps desconocidas" desde esa app: acéptalo y pulsa **Instalar**. Las versiones nuevas se instalan encima sin perder la carpeta segura.

## Limitaciones conocidas

- Los vídeos DASH (`.mpd`) y los protegidos con DRM no se pueden descargar.
- Si un vídeo HLS trae el audio en una pista separada, se descarga solo la pista de vídeo.
- El DNS cifrado de la app solo afecta a las descargas: para las páginas, activa el DNS privado de Android (Ajustes de Sailer → "DNS cifrado para todo el móvil").
- El APK se firma con una clave incluida en el repositorio (cómodo para uso personal, no apto para publicar en tiendas).

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

La lista de bloqueo procede de [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (licencia MIT).
