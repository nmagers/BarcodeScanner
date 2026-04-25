# Source this file (`. ./env.sh`) in a new shell before running ./gradlew.
# It points Gradle at the local Android SDK installed by setup-ubuntu.sh.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export ANDROID_HOME="$HERE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which javac 2>/dev/null)")")")}"
echo "Android SDK : $ANDROID_HOME"
echo "Java home   : $JAVA_HOME"
