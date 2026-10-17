<p align="center">
  <img src="linux/data/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg" width="96" height="96" alt="Saga">
</p>

<h1 align="center">Saga Apps</h1>

<p align="center">
  Apps nativas para <a href="https://github.com/yggdrasil-sh/saga/blob/main/README.es.md">Saga</a>, tu casa autoalojada para servicios, notas y tareas.<br>
  iPhone · iPad · Mac · Linux (GNOME) · Android
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="README.de.md">Deutsch</a> · <b>Español</b>
</p>

---

Las apps se conectan a tu propio servidor de Saga. Páginas, bases de datos, tareas y eventos se muestran y
editan de forma **nativa**, no en una web incrustada. Siguen el idioma del sistema: español, inglés o alemán
(cualquier otro idioma usa el inglés).

| Plataforma | Tecnología | Carpeta | Instrucciones |
|---|---|---|---|
| iOS, iPadOS, macOS | SwiftUI, WidgetKit | [`ios/`](ios/) | [ios/README.md](ios/README.md) |
| Linux (GNOME) | Python, GTK4 + libadwaita, RPM | [`linux/`](linux/) | [linux/README.md](linux/README.md) |
| Android | Kotlin, Jetpack Compose | [`android/`](android/) | [android/README.md](android/README.md) |

## Así se ve

<p align="center">
  <img src="docs/screenshots/ios-start.png" width="200" alt="iPhone: inicio con tareas y páginas recientes">
  <img src="docs/screenshots/ios-dunkel-board.png" width="200" alt="iPhone: base de datos como tablero en tema oscuro">
  <img src="docs/screenshots/android-dienste.png" width="200" alt="Android: servicios por grupos">
  <img src="docs/screenshots/android-heute.png" width="200" alt="Android: hoy con eventos y tareas">
</p>
<p align="center">
  <img src="docs/screenshots/ipad-projekte.png" width="620" alt="iPad: barra lateral y base de datos como tablero">
</p>

## Descargar

Los paquetes listos están en [Releases](https://github.com/yggdrasil-sh/saga-apps/releases/latest):

| Sistema | Archivo |
|---|---|
| macOS 26 o posterior | `Saga-<versión>-macOS.dmg`, firmado y notarizado por Apple |
| Linux (Fedora, AlmaLinux/RHEL 10 con EPEL) | `saga-<versión>-1.noarch.rpm` |
| Linux (cualquier distribución con Flatpak) | `saga-<versión>-x86_64.flatpak` (`flatpak install --user saga-….flatpak`) |
| Android 8 o posterior | `Saga-<versión>.apk` (permitir la instalación desde orígenes desconocidos) |

iPhone y iPad: compílala tú (ver [ios/README.md](ios/README.md)); todavía no hay versión en la App Store.

## Iniciar sesión

1. Introduce tu servidor de Saga (p. ej. `https://saga.example.org`) y pulsa **Iniciar sesión**: el inicio de
   sesión se abre en el navegador (cuenta local, LDAP o SSO, según el servidor).
2. O, más cómodo: en Saga, en el navegador, arriba en 👤 → **Ajustes** → **Apps y dispositivos** →
   **Iniciar sesión con código QR** y escanea el código con la app (Mac/Linux: pega el enlace de vinculación).

Después, las apps mantienen la sesión iniciada. Puedes cerrar la sesión de cualquier dispositivo en todo
momento en Saga, en "Apps y dispositivos".

## Publicar páginas

Con el servidor de Saga 1.2 o posterior, todas las apps pueden hacer pública una página: menú de la página →
**Publicar …**. La primera vez eliges un **nombre público** (aparece en la dirección `…/@name/…`; nunca es tu
nombre de usuario). Después defines la dirección, **Publicar también las subpáginas**, **Permitir buscadores** y
**Como página de perfil**, copias o abres el enlace, o eliges **Dejar de publicar**. Las páginas públicas muestran
**Pública** en la cabecera; tu nombre público se cambia en **Ajustes → Cuenta**. En servidores más antiguos no
aparece la opción del menú.

## Novedades de 2.1 (con el servidor de Saga 2.1)

- **Llamadas de audio y vídeo** en el chat directo, cifradas de extremo a extremo. iPhone/iPad suenan mediante
  CallKit (requiere una clave APNs en el servidor), Android como llamada a pantalla completa (en segundo plano con un
  distribuidor UnifiedPush), el Mac con su propia ventana de llamada; en Linux la app suena y la llamada se abre en el
  navegador.
- **Fotos de perfil** allí donde aparecen personas; foto propia con recorte en los ajustes.
- **Inicio de sesión y seguridad:** estado de las llaves de acceso y de la app de autenticación en los ajustes; el inicio de
  sesión con segundo factor se hace en la ventana del navegador.
- **Imágenes de chat rápidas** con miniaturas, **Immich en el chat**, pegar imágenes y copiar bloques de imagen.

Las funciones nuevas necesitan el servidor de Saga 2.1; con servidores más antiguos las apps funcionan como antes.

## Novedades de 2.0 (con el servidor de Saga 2.0)

- **Compartir y comentar:** comparte páginas con personas y grupos (ver, comentar, editar), comentarios en los
  bloques con respuestas y «Resolver».
- **Notificaciones:** una campana con todo lo que te afecta; en las páginas ves quién también está aquí o editando.
- **Chats** con cifrado de extremo a extremo: chats directos, de grupo y de página. La primera vez recibes una
  **clave de recuperación**; los dispositivos nuevos se confirman con un código y el **número de seguridad** lo
  comparas con la otra persona.
- **Notificaciones push** (nunca con contenido): iOS/iPadOS mediante Apple Push (el administrador introduce una clave
  APNs), Android y Linux mediante [UnifiedPush](https://unifiedpush.org), p. ej. con ntfy, sin servicios de Google.
- **Asistente de IA** (si el administrador lo activa): **Pregunta a tus páginas** y **Esta página**.
- **Federación:** las páginas de otras instancias de Saga aparecen en **Compartido conmigo**; compartir con
  `nombre@servidor`.

Las funciones nuevas necesitan el servidor de Saga 2.0. Todo es opcional: con servidores más antiguos las apps
funcionan como antes.

## Compilarlas tú

Los ajustes personales (equipo de desarrollo, ID de la app, dispositivos de prueba, comandos de distribución)
**nunca** están en el repositorio, sino en archivos locales que git ignora. Al lado de cada uno hay una
plantilla:

- `ios/Config/Local.xcconfig.example` → `ios/Config/Local.xcconfig`
- `ios/Config/local.env.example` → `ios/Config/local.env`
- `tools/local.env.example` → `tools/local.env` (equipos para las pruebas de Linux)

Versión de todas las apps: `tools/set-version.sh 2.1.0` (una sola fuente: `VERSION`). Cuando un cambio en
`VERSION` llega a `main`, GitHub compila todos los paquetes y los publica como release
(`.github/workflows/release.yml`). Lo nuevo llega primero a la rama `testing`: cada push allí compila todos los
paquetes y reemplaza la versión preliminar `v<versión>-testing` (pre-release, nunca «Latest»).

## Versiones

Las apps siguen [Semantic Versioning](https://semver.org/lang/es/). En sus ajustes muestran su propia versión y
la del servidor; si una app es demasiado antigua para el servidor, te avisa.

## Licencia

[GNU AGPL-3.0](LICENSE), igual que el propio Saga.
