#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:-}"
case "$PLATFORM" in
  paper|fabric|neoforge) ;;
  *)
    echo "usage: $0 <paper|fabric|neoforge>" >&2
    exit 2
    ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ROOT="$ROOT/build/e16-clean-boot/$PLATFORM"
MARKER="$RUN_ROOT/e16-boot-ok.marker"
LOG="$RUN_ROOT/server.log"
USER_AGENT="JarvisMinecraft-E16/0.1 (https://github.com/Kardane/JarvisMinecraft)"

rm -rf "$RUN_ROOT"
mkdir -p "$RUN_ROOT"
printf 'eula=true\n' > "$RUN_ROOT/eula.txt"
cat > "$RUN_ROOT/server.properties" <<'EOF'
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25565
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=1
white-list=false
motd=JARVIS E16 clean boot
EOF

download() {
  local url="$1"
  local target="$2"
  curl --fail --location --silent --show-error \
    --retry 3 --retry-delay 2 \
    -H "User-Agent: $USER_AGENT" \
    "$url" -o "$target"
}

paper_url() {
  python3 - "$USER_AGENT" <<'PY'
import json
import sys
import urllib.request

ua = sys.argv[1]
url = "https://fill.papermc.io/v3/projects/paper/versions/1.21.8/builds"
request = urllib.request.Request(url, headers={"User-Agent": ua})
with urllib.request.urlopen(request, timeout=30) as response:
    builds = json.load(response)

chosen = next((item for item in builds if item.get("channel") == "STABLE"), None)
if chosen is None:
    chosen = next((item for item in builds if item.get("channel") == "BETA"), None)
if chosen is None and builds:
    chosen = builds[0]
if chosen is None:
    raise SystemExit("No Paper 1.21.8 build is available.")

print(chosen["downloads"]["server:default"]["url"])
PY
}

fabric_installer_version() {
  python3 - "$USER_AGENT" <<'PY'
import json
import sys
import urllib.request

ua = sys.argv[1]
request = urllib.request.Request(
    "https://meta.fabricmc.net/v2/versions/installer",
    headers={"User-Agent": ua},
)
with urllib.request.urlopen(request, timeout=30) as response:
    installers = json.load(response)

chosen = next((item for item in installers if item.get("stable")), None)
if chosen is None and installers:
    chosen = installers[0]
if chosen is None:
    raise SystemExit("No Fabric installer is available.")

print(chosen["version"])
PY
}

run_server() {
  set +e
  (
    cd "$RUN_ROOT"
    timeout 300s "$@"
  ) >"$LOG" 2>&1
  local code=$?
  set -e

  if [[ $code -ne 0 ]]; then
    echo "E16 $PLATFORM server exited with code $code" >&2
    tail -n 200 "$LOG" >&2 || true
    exit $code
  fi

  if [[ ! -f "$MARKER" ]]; then
    echo "E16 $PLATFORM server did not produce the clean-boot marker." >&2
    tail -n 200 "$LOG" >&2 || true
    exit 1
  fi

  local expected="E16 "
  case "$PLATFORM" in
    paper) expected+="Paper clean boot OK" ;;
    fabric) expected+="Fabric clean boot OK" ;;
    neoforge) expected+="NeoForge clean boot OK" ;;
  esac

  if [[ "$(tr -d '\r\n' < "$MARKER")" != "$expected" ]]; then
    echo "E16 $PLATFORM marker content was invalid." >&2
    cat "$MARKER" >&2
    exit 1
  fi

  echo "E16 $PLATFORM clean boot OK"
}

case "$PLATFORM" in
  paper)
    ARTIFACT="$ROOT/minecraft/paper/build/libs/jarvisminecraft-paper.jar"
    [[ -f "$ARTIFACT" ]] || {
      echo "Missing Paper artifact: $ARTIFACT" >&2
      exit 1
    }

    mkdir -p "$RUN_ROOT/plugins"
    cp "$ARTIFACT" "$RUN_ROOT/plugins/jarvisminecraft-paper.jar"
    PAPER_URL="$(paper_url)"
    download "$PAPER_URL" "$RUN_ROOT/paper.jar"

    run_server java \
      -Xms512M -Xmx1024M \
      -Djarvis.e16BootSmoke=true \
      "-Djarvis.e16BootMarker=$MARKER" \
      -jar paper.jar --nogui
    ;;

  fabric)
    ARTIFACT="$ROOT/minecraft/fabric/build/libs/jarvisminecraft-fabric.jar"
    [[ -f "$ARTIFACT" ]] || {
      echo "Missing Fabric artifact: $ARTIFACT" >&2
      exit 1
    }

    mkdir -p "$RUN_ROOT/mods"
    cp "$ARTIFACT" "$RUN_ROOT/mods/jarvisminecraft-fabric.jar"
    download \
      "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.133.4+1.21.8/fabric-api-0.133.4+1.21.8.jar" \
      "$RUN_ROOT/mods/fabric-api.jar"

    INSTALLER="$(fabric_installer_version)"
    download \
      "https://meta.fabricmc.net/v2/versions/loader/1.21.8/0.17.2/$INSTALLER/server/jar" \
      "$RUN_ROOT/fabric-server.jar"

    run_server java \
      -Xms512M -Xmx1024M \
      -Djarvis.e16BootSmoke=true \
      "-Djarvis.e16BootMarker=$MARKER" \
      -jar fabric-server.jar nogui
    ;;

  neoforge)
    ARTIFACT="$ROOT/minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar"
    [[ -f "$ARTIFACT" ]] || {
      echo "Missing NeoForge artifact: $ARTIFACT" >&2
      exit 1
    }

    download \
      "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.8.52/neoforge-21.8.52-installer.jar" \
      "$RUN_ROOT/neoforge-installer.jar"

    (
      cd "$RUN_ROOT"
      java -jar neoforge-installer.jar --installServer
    ) >"$RUN_ROOT/install.log" 2>&1

    mkdir -p "$RUN_ROOT/mods"
    cp "$ARTIFACT" "$RUN_ROOT/mods/jarvisminecraft-neoforge.jar"
    cat > "$RUN_ROOT/user_jvm_args.txt" <<EOF
-Xms512M
-Xmx1024M
-Djarvis.e16BootSmoke=true
-Djarvis.e16BootMarker=$MARKER
EOF

    run_server bash run.sh nogui
    ;;
esac
