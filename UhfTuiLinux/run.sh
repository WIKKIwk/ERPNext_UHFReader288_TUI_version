#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/UhfTuiLinux"
SDK_JAR="${SDK_JAR:-}"

if [[ -z "${SDK_JAR}" ]]; then
  if [[ -f "$ROOT_DIR/lib/CReader.jar" ]]; then
    SDK_JAR="$ROOT_DIR/lib/CReader.jar"
  elif [[ -f "$ROOT_DIR/SDK/Java-linux/CReader.jar" ]]; then
    SDK_JAR="$ROOT_DIR/SDK/Java-linux/CReader.jar"
  fi
fi

if [[ ! -f "$SDK_JAR" ]]; then
  echo "SDK topilmadi."
  echo "CReader.jar ni repo ichiga qo'ying: $ROOT_DIR/lib/CReader.jar"
  echo "Yoki SDK_JAR=/path/to/CReader.jar ./UhfTuiLinux/run.sh"
  exit 1
fi

JAVAC_BIN=""
JAVA_BIN=""

ensure_jdk() {
  if command -v javac >/dev/null 2>&1; then
    JAVAC_BIN="$(command -v javac)"
    JAVA_BIN="$(command -v java)"
    return 0
  fi

  local arch
  arch="$(uname -m)"
  if [[ "$arch" != "x86_64" ]]; then
    echo "javac topilmadi va arch '$arch' avtomatik JDK download uchun qo'llanmaydi." >&2
    echo "Iltimos JDK o'rnating (masalan Arch: sudo pacman -S jdk-openjdk)." >&2
    return 1
  fi

  local cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/uhftui-linux"
  local jdk_dir="$cache_dir/jdk-17"
  local javac_bin="$jdk_dir/bin/javac"
  if [[ -x "$javac_bin" ]]; then
    JAVAC_BIN="$javac_bin"
    JAVA_BIN="$jdk_dir/bin/java"
    return 0
  fi

  mkdir -p "$cache_dir"
  echo "javac topilmadi. Local JDK 17 yuklab olinadi: $jdk_dir" >&2

  local tmp_dir
  tmp_dir="$(mktemp -d)"
  # Intentionally no EXIT trap: this function is called in a command substitution and we run with `set -u`.

  local tarball="$tmp_dir/jdk.tar.gz"
  local url="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$tarball" "$url"
  else
    wget -O "$tarball" "$url"
  fi

  rm -rf "$jdk_dir"
  mkdir -p "$jdk_dir"
  tar -xzf "$tarball" -C "$tmp_dir"

  local extracted
  extracted="$(find "$tmp_dir" -maxdepth 1 -type d -name "jdk*" | head -n 1)"
  if [[ -z "$extracted" ]]; then
    echo "JDK extract bo'lmadi." >&2
    rm -rf "$tmp_dir"
    return 1
  fi

  mv "$extracted"/* "$jdk_dir"/
  rm -rf "$tmp_dir"

  if [[ ! -x "$javac_bin" ]]; then
    echo "JDK o'rnatildi, lekin javac topilmadi: $javac_bin" >&2
    return 1
  fi

  JAVAC_BIN="$javac_bin"
  JAVA_BIN="$jdk_dir/bin/java"
  return 0
}

if ! ensure_jdk; then
  exit 1
fi
if [[ -z "$JAVAC_BIN" || ! -x "$JAVAC_BIN" || -z "$JAVA_BIN" || ! -x "$JAVA_BIN" ]]; then
  echo "JDK topilmadi (javac/java)." >&2
  exit 1
fi

OUT_DIR="$APP_DIR/out"
mkdir -p "$OUT_DIR"

rm -rf "$OUT_DIR"/*
"$JAVAC_BIN" -encoding UTF-8 -cp "$SDK_JAR" -d "$OUT_DIR" "$APP_DIR/src/UhfTuiLinux.java"

exec "$JAVA_BIN" -cp "$OUT_DIR:$SDK_JAR" UhfTuiLinux
