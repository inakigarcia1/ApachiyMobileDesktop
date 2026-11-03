# Apachiy Desktop y Mobile (Apachiy-DesktopMobile)

Migrado desde `.cursor/rules/` (apachiy-repos, apachiy-independence, apachiy-playstore-bundles, commit-finished-work). Las reglas con alcance por archivo están en `.claude/rules/` y el loop de QA de subtítulos en la skill `.claude/skills/apachiy-subsync-loop/`.

## Dónde viven las apps

Este repo (`Apachiy-DesktopMobile`) y sus worktrees son el único lugar de Apachiy Desktop y Apachiy Mobile.

No uses `Apachiy-Mobile`. Ese repo no existe: no lo abras, no lo edites y no generes bundles ahí.

Mobile es `composeApp`, `androidApp` e `iosApp` de este repo. Desktop es el resto de este mismo repo. TV está en `D:\Proyectos\Apachiy-Repos\Apachiy-NuvioFork`.

Si un cambio es para mobile y TV, aplicalo acá y en el repo de TV.

El código de desktop y mobile se commitea y se pushea a `inakigarcia1/ApachiyMobileDesktop` (este repo). `inakigarcia1/ApachiyDesktop` es solo el repo de releases que mira el updater de PC y del APK sideload. Ahí se suben instaladores y APKs. No se clona para editar, no se commitea código y no se pushea código.

Producción Apachiy: MCP **Coolify** disponible; SSH desde este PC con `ssh guardia@51.222.140.201`. Detalle en `D:\Proyectos\Apachiy-Repos\Apachiy\CLAUDE.md`.

## Independencia de Nuvio/Stremio

Apachiy es su propio producto. No trates el comportamiento de Nuvio ni el protocolo de addons de Stremio como una restricción.

Si un protocolo, forma de respuesta o flujo del cliente estorba, cambialo. La compatibilidad con addons de terceros no es un objetivo: los usuarios no pueden instalar addons. Preferí un request simple antes que mantener un GET que existe solo porque Stremio lo definió.

## Bundles de Play Store

Cuando Iñaki pida generar bundles de Play Store (AAB), siempre es:

- **Edición:** `release` (nunca `debug`)
- **Flavor:** `playstore` (nunca `full` ni sideload)
- **Backend:** **cloud** (producción: `api.apachiy.org`, Supabase cloud, etc.). **Jamás** infra local.

No uses `assembleFull*`, `bundleFull*`, `installFull*`, ni variantes debug para esa tarea.

Los bundles **siempre** van contra cloud. Iñaki no pide bundles apuntando a localhost, `127.0.0.1`, `10.0.2.2`, `local.dev.properties`, ni `APACHIY_USE_LOCAL_DEV`. No uses `-Pnuvio.useLocalDev=true`, `-Papachiy.useLocalDev=true`, ni scripts `run-*-local*`. Quitá `APACHIY_USE_LOCAL_DEV` del entorno antes de compilar. Config = `local.properties` (cloud), no el overlay de dev local.

**Mobile (este repo):** subí `versionCode` en `iosApp/Configuration/Version.xcconfig` (`CURRENT_PROJECT_VERSION`) antes de compilar. Luego:

```text
:androidApp:bundlePlaystoreRelease
-Pnuvio.android.distribution=playstore
-Pnuvio.useLocalDev=false
```

Cloud = `local.properties`, sin `APACHIY_USE_LOCAL_DEV`. En PowerShell, los `-P` van entre comillas.

Salida: `androidApp/build/outputs/bundle/playstoreRelease/androidApp-playstore-release.aab`

**TV:** repo `Apachiy-NuvioFork`, misma convención release + playstore. Subí `versionCode` en `app/build.gradle.kts`, luego `:app:bundlePlaystoreRelease` con `-Papachiy.useLocalDev=false`.

## Commitear el trabajo terminado

El trabajo que terminaste en Apachiy-NuvioFork (TV) o Apachiy-DesktopMobile (desktop y mobile) se commitea antes de parar, cambiar de branch o devolver el árbol.

- Stageá solo los archivos de ese cambio. No toques archivos sucios ajenos.
- Un commit por cambio. No mezcles un movimiento de flavor con un fix de sync.
- No commitees secretos, `.env`, APKs ni logs de build.
- Si la tarea vive en otra branch, commitealá en esa branch antes de hacer checkout.
- Decí el asunto del commit al terminar. Un árbol sucio que cambiaste no está terminado.
