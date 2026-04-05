#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JDK_DIR="${HOME}/.local/jdks/temurin-17"
SDK_ROOT="${HOME}/.local/android-sdk"
CMDLINE_TOOLS_DIR="${SDK_ROOT}/cmdline-tools/latest"
JDK_ARCHIVE="${HOME}/.local/jdks/temurin17.tar.gz"
ANDROID_TOOLS_ARCHIVE="${SDK_ROOT}/cmdline-tools.zip"

JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse}"
ANDROID_TOOLS_URL="${ANDROID_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-mac-14742923_latest.zip}"

mkdir -p "${HOME}/.local/jdks" "${SDK_ROOT}/cmdline-tools"

download_resume() {
  local url="$1"
  local output="$2"
  if [[ -f "$output" ]]; then
    curl -L -C - "$url" -o "$output"
  else
    curl -L "$url" -o "$output"
  fi
}

if [[ ! -d "${JDK_DIR}" ]]; then
  echo "[android-env] Downloading JDK from ${JDK_URL}"
  download_resume "${JDK_URL}" "${JDK_ARCHIVE}"
  rm -rf "${JDK_DIR}" "${HOME}/.local/jdks/temurin-17-tmp"
  mkdir -p "${HOME}/.local/jdks/temurin-17-tmp" "${JDK_DIR}"
  tar -xzf "${JDK_ARCHIVE}" -C "${HOME}/.local/jdks/temurin-17-tmp"
  mv "${HOME}/.local/jdks/temurin-17-tmp"/* "${JDK_DIR}"
  rmdir "${HOME}/.local/jdks/temurin-17-tmp"
fi

if [[ ! -d "${CMDLINE_TOOLS_DIR}" ]]; then
  echo "[android-env] Downloading Android command line tools from ${ANDROID_TOOLS_URL}"
  download_resume "${ANDROID_TOOLS_URL}" "${ANDROID_TOOLS_ARCHIVE}"
  rm -rf "${SDK_ROOT}/cmdline-tools/unpacked" "${CMDLINE_TOOLS_DIR}"
  mkdir -p "${SDK_ROOT}/cmdline-tools/unpacked"
  unzip -q -o "${ANDROID_TOOLS_ARCHIVE}" -d "${SDK_ROOT}/cmdline-tools/unpacked"
  mv "${SDK_ROOT}/cmdline-tools/unpacked/cmdline-tools" "${CMDLINE_TOOLS_DIR}"
  rmdir "${SDK_ROOT}/cmdline-tools/unpacked"
fi

export JAVA_HOME="${JDK_DIR}/Contents/Home"
if [[ ! -d "${JAVA_HOME}" ]]; then
  JAVA_HOME="$(find "${JDK_DIR}" -maxdepth 3 -type d -path '*/Contents/Home' | head -n 1)"
fi

if [[ -z "${JAVA_HOME}" || ! -d "${JAVA_HOME}" ]]; then
  echo "[android-env] Failed to determine JAVA_HOME under ${JDK_DIR}" >&2
  exit 1
fi
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export ANDROID_HOME="${SDK_ROOT}"
export PATH="${JAVA_HOME}/bin:${CMDLINE_TOOLS_DIR}/bin:${SDK_ROOT}/platform-tools:${PATH}"

yes | sdkmanager --licenses >/dev/null
sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

cat <<EOF
[android-env] Ready
JAVA_HOME=${JAVA_HOME}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}

Use:
  export JAVA_HOME="${JAVA_HOME}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT}"
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
  export PATH="\$JAVA_HOME/bin:${CMDLINE_TOOLS_DIR}/bin:\$ANDROID_SDK_ROOT/platform-tools:\$PATH"

Then run:
  ./android/gradlew -p android tasks
  npx expo run:android
EOF
