# Sailer

Navegador Android personal, ligero y centrado en la privacidad.

## Qué hace (versión 0.1)

- **Pestañas** y **barra de direcciones** (si escribes algo que no es una web, busca en DuckDuckGo).
- **Incógnito siempre**: al salir se borran cookies, caché, historial, formularios y almacenamiento de las webs. Si la app se cierra de golpe, se borra al volver a abrirla.
- **Solo HTTPS**: las direcciones `http://` se convierten en `https://` y las conexiones sin cifrar o con certificados falsos se bloquean.
- **Sin capturas**: no se pueden hacer capturas ni grabaciones de pantalla, y en "apps recientes" la vista previa sale en negro.
- Sin ubicación, cámara ni micrófono para las webs, sin ventanas emergentes, sin copias de seguridad en la nube y sin enviar datos a Google (Safe Browsing ni estadísticas de WebView).

## Cómo instalarlo en el móvil (sin Android Studio)

1. En GitHub, abre la pestaña **Actions** del repositorio.
2. Pulsa en la ejecución más reciente de **Compilar APK** que tenga el círculo verde ✅.
3. Baja hasta **Artifacts** y descarga **Sailer-apk**. Es un `.zip`: descomprímelo y dentro está `app-debug.apk`.
4. Pasa el `.apk` al móvil (o haz todo esto directamente desde el navegador del móvil) y ábrelo.
5. Android pedirá permiso para "instalar apps desconocidas" desde esa app (Archivos, Chrome...). Acéptalo y pulsa **Instalar**.

Las siguientes versiones se instalan encima de la anterior de la misma forma.

## Hoja de ruta

1. ✅ Base del navegador: pestañas, incógnito permanente, solo HTTPS, sin capturas.
2. Bloqueador de anuncios, rastreadores y pop-ups; bloqueo con PIN/huella; DNS cifrado.
3. Descargador de vídeo, imágenes y audio (incluido HLS, sin DRM) a una carpeta cifrada.
4. Pulido y APK final firmado.

## Estructura del código

```
app/src/main/java/com/sailer/browser/
├── MainActivity.kt          Pantalla principal (barra, menú, pestañas)
├── SailerApp.kt             Limpieza al arrancar
├── privacy/PrivacyManager   Borrado de todos los datos de navegación
├── tabs/                    Lista de pestañas
└── web/
    ├── BrowserWebViewFactory  Configuración privada de cada WebView
    ├── SailerWebViewClient    Forzar HTTPS, certificados, filtro de peticiones
    ├── SailerChromeClient     Progreso, títulos, pantalla completa, permisos
    ├── RequestFilter          Enganche para el bloqueador (paso 2)
    ├── DownloadHandler        Enganche para el descargador (paso 3)
    └── UrlUtils               Convierte lo que escribes en una dirección
```
