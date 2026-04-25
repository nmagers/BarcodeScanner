# Barcode Scanner

A small Android app that scans barcodes with the camera and saves them to a local
database you can export as CSV.

## Features

- Scan any common barcode format (QR, EAN-13/8, UPC-A/E, Code 128/39/93, PDF417, Aztec, Data Matrix, ITF, Codabar)
- **Automatic product lookup** for retail barcodes (EAN/UPC) via Open Food Facts and UPCitemdb — product name and size appear in the list and are saved to the CSV
- **Tap to edit** — tap any row to open a dialog with editable Name and Size fields and a Unit dropdown (Single / Case / Block) for stocktaking
- **Duplicate prevention** — the same barcode can't be recorded twice; re-scanning an existing code shows a toast with its known product
- Results persist across app restarts (Room / SQLite)
- Export the full list to a timestamped CSV file (`barcodes_YYYYMMDD_HHMMSS.csv`) and share it via email, Drive, Messages, etc.
- Delete individual entries or clear everything

## Product lookup

When you scan a numeric retail barcode (EAN-13, EAN-8, UPC-A, UPC-E, ITF), the
app does a best-effort product lookup in the background:

1. **Open Food Facts** (`world.openfoodfacts.org`) — free, no API key, excellent coverage for anything grocery/consumer-packaged. Queried first.
2. **UPCitemdb trial** (`api.upcitemdb.com`) — free fallback with broader (non-food) coverage. Limited to 100 requests/day per IP; no key needed.

The lookup runs after the scan is saved, so offline or unknown products don't
block anything — the barcode is kept regardless and the product column stays
empty. QR codes and arbitrary text barcodes skip lookup entirely (the concept
doesn't apply).

## How it works

Uses Google's **ML Kit Code Scanner** (`play-services-code-scanner`). This API
handles the entire camera UI itself — no `CAMERA` permission is needed in the
manifest, because the scanning screen runs in Google Play Services' own process.
The downside is that the device needs Google Play Services (fine on all Google-
certified phones; won't work on Huawei devices without GMS or plain AOSP builds).

If you want a no-Play-Services build, swap the scanner call out for CameraX +
`com.google.mlkit:barcode-scanning` and add a `CAMERA` permission — the rest of
the app (storage, CSV export) stays the same.

---

## Building on Ubuntu (command line, no Android Studio)

### One-shot setup

From the project root, run:

```bash
./setup-ubuntu.sh
```

That script:

1. `apt install`s OpenJDK 17, `unzip`, `wget` (needs `sudo`)
2. Downloads the Android command-line tools to `./android-sdk` (no sudo, no system-wide install)
3. Accepts the SDK licenses and installs `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
4. Downloads Gradle 8.7 temporarily and runs `gradle wrapper` to create `./gradlew`
5. Writes `local.properties` pointing Gradle at the local SDK
6. Runs `./gradlew assembleDebug` — produces `app/build/outputs/apk/debug/app-debug.apk`

Tested on Ubuntu 22.04 and 24.04. It only needs `sudo` for the apt step; the
Android SDK lives entirely inside the project folder so you can nuke it by
deleting `./android-sdk`.

### Subsequent builds

Once setup has run, future builds are just:

```bash
source ./env.sh          # sets ANDROID_HOME for this shell
./gradlew assembleDebug  # debug APK
./gradlew installDebug   # build + install to connected device
./gradlew clean          # wipe build outputs
```

If you'd rather not `source env.sh` every time, add these to your shell rc:

```bash
export ANDROID_HOME="$HOME/path/to/BarcodeScanner/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### Installing on a device

1. Enable **Developer options** → **USB debugging** on the phone (tap *Build number* in Settings → About Phone seven times to unlock).
2. Plug it in, accept the RSA fingerprint prompt on the phone.
3. `adb devices` — should show your device.
4. `./gradlew installDebug`.

Or copy `app/build/outputs/apk/debug/app-debug.apk` to the phone manually and
tap it to install (you'll need to allow install-from-unknown-sources for your
file manager).

### Manual setup (if you'd rather not run the script)

<details>
<summary>Step-by-step equivalents</summary>

```bash
# 1. JDK
sudo apt install openjdk-17-jdk unzip wget

# 2. Android SDK command-line tools
mkdir -p android-sdk/cmdline-tools
cd android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools latest
cd ../..

export ANDROID_HOME="$PWD/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 3. SDK packages
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 4. Gradle wrapper (one-time — download Gradle, generate wrapper)
wget https://services.gradle.org/distributions/gradle-8.7-bin.zip
unzip gradle-8.7-bin.zip
./gradle-8.7/bin/gradle wrapper --gradle-version 8.7
rm -rf gradle-8.7 gradle-8.7-bin.zip

# 5. Tell Gradle where the SDK is
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 6. Build
./gradlew assembleDebug
```

</details>

### Troubleshooting

**`SDK location not found`** — make sure `local.properties` exists at the project root with `sdk.dir=/absolute/path/to/android-sdk`, or that `ANDROID_HOME` is exported.

**`Could not resolve all artifacts`** — network issue. Gradle needs to reach `dl.google.com` and `repo.maven.org` on the first build. Corporate firewalls/VPNs can block these.

**Build succeeds but phone rejects the APK** — this is a debug APK signed with the default debug key. If you already have it installed signed with a *different* key (e.g. a release build), uninstall the existing copy first: `adb uninstall com.example.barcodescanner`.

**`adb: no devices`** — USB debugging not enabled, USB cable is charge-only, or you haven't accepted the RSA prompt. On some phones you also need to switch USB mode to "File transfer" instead of "Charging only".

**cmdline-tools version 404s** — Google occasionally rotates these. Grab the current URL from <https://developer.android.com/studio> (scroll to "Command line tools only") and update `CMDLINE_TOOLS_VERSION` in `setup-ubuntu.sh`.

---

## CSV format

```
id,value,format,product,size,unit,scanned_at
1,9310072001234,EAN_13,Vegemite,220g,Case,2026-04-17T14:22:05+10:00
2,9300675020428,EAN_13,Bundaberg Ginger Beer,375 mL,Block,2026-04-17T14:22:48+10:00
3,https://example.com,QR_CODE,,,Single,2026-04-17T14:23:12+10:00
```

- `product` and `size` are empty when the lookup didn't find a match (and for non-retail barcodes)
- `unit` defaults to `Single` and can be changed by tapping the row (options: Single, Case, Block)
- Fields containing commas, quotes, or newlines are wrapped in double quotes (RFC 4180 style)
- Timestamps are ISO-8601 with the device's local timezone offset
- UTF-8 encoded

## Project layout

```
BarcodeScanner/
├── setup-ubuntu.sh           — one-shot toolchain install + first build
├── env.sh                    — source this for later builds
├── build.gradle.kts          — root build script
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts      — module build script + dependencies
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/barcodescanner/
        │   ├── MainActivity.kt       — Compose UI + scanner + share intent
        │   ├── MainViewModel.kt      — State + CSV writer
        │   ├── BarcodeEntry.kt       — Room entity
        │   ├── BarcodeDao.kt         — Room DAO
        │   └── BarcodeDatabase.kt    — Room database
        └── res/
            ├── values/strings.xml
            ├── values/themes.xml
            └── xml/file_paths.xml    — FileProvider paths for CSV sharing
```

## Changing the package name

Everything is under `com.example.barcodescanner`. To rename:

1. Update `applicationId` and `namespace` in `app/build.gradle.kts`.
2. Rename the `java/com/example/barcodescanner/` folder to match.
3. Update the `package` declaration at the top of each `.kt` file.
4. `./gradlew clean assembleDebug`.
