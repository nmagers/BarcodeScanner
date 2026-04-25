#!/usr/bin/env bash
# Sets up an Ubuntu machine to build this project from the command line.
# Installs JDK 17, downloads the Android SDK command-line tools into ./android-sdk,
# installs the SDK packages this project needs, generates the Gradle wrapper, and
# runs the first build.
#
# Tested on Ubuntu 22.04 and 24.04. Needs sudo only for the apt step.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$PROJECT_DIR/android-sdk"
GRADLE_DIR="$PROJECT_DIR/.gradle-bootstrap"

# Pin versions — bump these over time.
GRADLE_VERSION="8.7"
CMDLINE_TOOLS_VERSION="11076708"   # Jan 2024 release; any recent one works
ANDROID_PLATFORM="android-34"
ANDROID_BUILD_TOOLS="34.0.0"

say() { printf "\n\033[1;34m==>\033[0m %s\n" "$*"; }

# 1. System packages --------------------------------------------------------
say "Installing system packages (JDK 17, unzip, wget)"
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip wget curl

export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
say "JAVA_HOME = $JAVA_HOME"

# 2. Android SDK command-line tools ----------------------------------------
if [[ ! -d "$SDK_DIR/cmdline-tools/latest" ]]; then
    say "Downloading Android command-line tools"
    mkdir -p "$SDK_DIR/cmdline-tools"
    TMP_ZIP="$(mktemp --suffix=.zip)"
    wget -q --show-progress \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
        -O "$TMP_ZIP"
    unzip -q "$TMP_ZIP" -d "$SDK_DIR/cmdline-tools"
    # Google ships them in a folder called "cmdline-tools" — rename to "latest"
    # so the SDK layout matches what sdkmanager expects.
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm "$TMP_ZIP"
else
    say "Android command-line tools already present"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

# 3. SDK packages + licenses -----------------------------------------------
say "Accepting SDK licenses"
yes | sdkmanager --licenses > /dev/null || true

say "Installing SDK packages"
sdkmanager \
    "platform-tools" \
    "platforms;${ANDROID_PLATFORM}" \
    "build-tools;${ANDROID_BUILD_TOOLS}"

# 4. Gradle wrapper --------------------------------------------------------
if [[ ! -f "$PROJECT_DIR/gradlew" ]]; then
    say "Bootstrapping Gradle ${GRADLE_VERSION} to generate the wrapper"
    mkdir -p "$GRADLE_DIR"
    if [[ ! -d "$GRADLE_DIR/gradle-${GRADLE_VERSION}" ]]; then
        TMP_ZIP="$(mktemp --suffix=.zip)"
        wget -q --show-progress \
            "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
            -O "$TMP_ZIP"
        unzip -q "$TMP_ZIP" -d "$GRADLE_DIR"
        rm "$TMP_ZIP"
    fi
    "$GRADLE_DIR/gradle-${GRADLE_VERSION}/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION"
else
    say "Gradle wrapper already present"
fi

# 5. Write local.properties so Gradle finds the SDK ------------------------
cat > "$PROJECT_DIR/local.properties" <<EOF
sdk.dir=$SDK_DIR
EOF
say "Wrote local.properties"

# 6. First build -----------------------------------------------------------
say "Running first build: ./gradlew assembleDebug"
cd "$PROJECT_DIR"
./gradlew assembleDebug

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK_PATH" ]]; then
    printf "\n\033[1;32m✓ Build succeeded.\033[0m APK: %s\n" "$APK_PATH"
    printf "\nInstall to a connected device with:\n  ./gradlew installDebug\n"
    printf "Or manually:\n  adb install -r %s\n\n" "$APK_PATH"
else
    printf "\n\033[1;31m✗ Build finished but APK not found at expected path.\033[0m\n"
    exit 1
fi
