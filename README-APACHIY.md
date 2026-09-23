# Apachiy Desktop + Mobile

Monorepo unificado de **Apachiy Desktop** y **Apachiy Mobile** (forks de Nuvio Desktop / Nuvio Mobile).

- Kotlin package: `com.nuvio.app`
- Android application id: `com.apachiy.app` (debug: `com.apachiy.app.debug`)
- Desktop macOS bundle id: `com.apachiy.desktop`
- Cloud: `supabase.apachiy.org` / `api.apachiy.org`

## Setup

1. Copiá `local.example.properties` a `local.properties` (gitignored).
2. Completá `APACHIY_SUPABASE_URL`, `APACHIY_SUPABASE_ANON_KEY`, `APACHIY_API_BASE_URL` y las keys de integraciones que uses.
3. Dejá `NUVIO_SUPABASE_FALLBACK_URL` vacío.
4. Sync Gradle.

## Mobile (Android APK)

```powershell
.\gradlew.bat :androidApp:assembleFullDebug -Pnuvio.android.distribution=full
.\gradlew.bat :androidApp:assembleFullRelease -Pnuvio.android.distribution=full
```

Variante **fullDebug** → `com.apachiy.app.debug`

## Desktop

```powershell
.\gradlew.bat :composeApp:run
.\gradlew.bat :composeApp:packageReleaseMsi
```

### Desktop contra backend local (sin tocar cloud)

1. Copiá `local.dev.example.properties` → `local.dev.properties` (o usá el script, que lo crea solo).
2. Ajustá URLs/puertos si tu `docker compose` usa otros.
3. Levantá el stack local y ejecutá:

```powershell
.\scripts\run-desktop-local.ps1
```

Equivalente manual:

```powershell
.\gradlew.bat :composeApp:run -Pnuvio.useLocalDev=true
```

`local.properties` sigue apuntando a producción (`supabase.apachiy.org`, `api.apachiy.org`). Solo con el flag `nuvio.useLocalDev` (o `APACHIY_USE_LOCAL_DEV=1`) se aplican los overrides de `local.dev.properties`.

### Mobile contra backend local

En `local.dev.properties` dejá **localhost** (igual que desktop). Al compilar/instalar para el emulador, Gradle reescribe a **10.0.2.2** automáticamente.

```powershell
.\scripts\run-android-local.ps1
```

Equivalente manual (PowerShell: comillas en `-P…`):

```powershell
.\gradlew.bat ":androidApp:installFullDebug" '-Pnuvio.android.distribution=full' '-Pnuvio.useLocalDev=true'
```

Teléfono físico en la LAN: `-Pnuvio.android.localDevHost=192.168.x.x`.

En macOS/Linux también: `packageReleaseDmg`, `packageReleaseDeb`, etc.

## Tests

```powershell
.\gradlew.bat :composeApp:desktopTest
.\gradlew.bat :composeApp:testDebugUnitTest -Pnuvio.android.distribution=full
```

## Versiones

- **Mobile / iOS**: `iosApp/Configuration/Version.xcconfig`
- **Desktop**: `composeApp/Configuration/DesktopVersion.properties`

## Estructura

| Módulo | Rol |
|--------|-----|
| `composeApp` | KMP compartido (commonMain + android/ios/desktop) |
| `androidApp` | APK Android (`com.apachiy.app`) |
| `desktopSentry` | Sentry source context para desktop |
| `iosApp` | Shell Xcode (iOS) |

La identidad de cliente (`apachiy-mobile` vs `apachiy-desktop`) se resuelve en runtime vía `AppVersionPolicy` por plataforma.
