#!/usr/bin/env bash
set -euo pipefail

echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<unset>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
echo

echo "[check] java"
if command -v java >/dev/null 2>&1; then
  java -version || true
else
  echo "java not found"
fi
echo

echo "[check] javac"
if command -v javac >/dev/null 2>&1; then
  javac -version || true
else
  echo "javac not found"
fi
echo

echo "[check] sdkmanager"
if command -v sdkmanager >/dev/null 2>&1; then
  sdkmanager --version || true
else
  echo "sdkmanager not found"
fi
echo

echo "[check] adb"
if command -v adb >/dev/null 2>&1; then
  adb version || true
else
  echo "adb not found"
fi
echo

echo "[check] gradle wrapper"
./android/gradlew -p android -version || true
