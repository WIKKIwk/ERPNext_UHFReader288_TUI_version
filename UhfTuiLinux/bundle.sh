#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/UhfTuiLinux"
DIST_DIR="${DIST_DIR:-$APP_DIR/dist}"
SDK_JAR="${SDK_JAR:-}"

resolve_sdk() {
  if [[ -n "${SDK_JAR}" && -f "${SDK_JAR}" ]]; then
    echo "${SDK_JAR}"
    return 0
  fi
  local candidates=(
    "$ROOT_DIR/lib/CReader.jar"
    "$ROOT_DIR/SDK/Java-linux/CReader.jar"
    "$ROOT_DIR/../ST-8504 New SDK/ST-8504 SDK/SDK/Java-linux/CReader.jar"
  )
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      echo "$c"
      return 0
    fi
  done
  if [[ -d "$HOME/Downloads" ]]; then
    local found
    found="$(find "$HOME/Downloads" -maxdepth 6 -type f -name 'CReader.jar' -print -quit 2>/dev/null || true)"
    if [[ -n "$found" && -f "$found" ]]; then
      echo "$found"
      return 0
    fi
  fi
  return 1
}

JDK_HOME=""

ensure_jdk() {
  if command -v javac >/dev/null 2>&1; then
    local javac_bin
    javac_bin="$(command -v javac)"
    JDK_HOME="$(cd -- "$(dirname -- "$javac_bin")/.." && pwd)"
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
    JDK_HOME="$jdk_dir"
    return 0
  fi

  mkdir -p "$cache_dir"
  echo "javac topilmadi. Local JDK 17 yuklab olinadi: $jdk_dir" >&2

  local tmp_dir
  tmp_dir="$(mktemp -d)"
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

  JDK_HOME="$jdk_dir"
  return 0
}

SDK_JAR="$(resolve_sdk || true)"
if [[ -z "${SDK_JAR}" || ! -f "${SDK_JAR}" ]]; then
  echo "SDK topilmadi. CReader.jar ni ko'rsating: SDK_JAR=/path/to/CReader.jar" >&2
  exit 1
fi

if ! ensure_jdk; then
  exit 1
fi

JAVA_BIN="$JDK_HOME/bin/java"
JAVAC_BIN="$JDK_HOME/bin/javac"
if [[ ! -x "$JAVA_BIN" || ! -x "$JAVAC_BIN" ]]; then
  echo "JDK binarilari topilmadi: $JDK_HOME" >&2
  exit 1
fi

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/app" "$DIST_DIR/lib" "$DIST_DIR/jre"

echo "Compile..."
find "$APP_DIR/src" -name "*.java" > "$DIST_DIR/sources.txt"
"$JAVAC_BIN" -encoding UTF-8 -cp "$SDK_JAR" -d "$DIST_DIR/app" @"$DIST_DIR/sources.txt"
rm -f "$DIST_DIR/sources.txt"

echo "Copy SDK..."
cp -f "$SDK_JAR" "$DIST_DIR/lib/CReader.jar"

echo "Copy runtime..."
cp -a "$JDK_HOME" "$DIST_DIR/jre"

cat > "$DIST_DIR/start.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAVA="$DIR/jre/bin/java"
if [[ ! -x "$JAVA" ]]; then
  echo "Local JRE topilmadi: $JAVA" >&2
  exit 1
fi
exec "$JAVA" -cp "$DIR/app:$DIR/lib/CReader.jar" uhf.tui.Main
EOF
chmod +x "$DIST_DIR/start.sh"

echo "Done. Standalone bundle: $DIST_DIR"
