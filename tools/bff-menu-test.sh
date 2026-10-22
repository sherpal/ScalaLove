#!/usr/bin/env bash
# Plays a game through Battle for Flatland's menu server, like a player with the browser frontend:
# the LÖVE client logs in, creates a game, gets ready, starts it, receives the game server
# credentials on the lobby websocket, then plays. Headless by default, in a window with --window.
#
# Runs the menu server (in a temporary working directory, where it keeps its JSON "database"),
# the game server launcher, and stops them (and the game server) at the end.
#
# Prerequisites:
#   - in BattleForFlatland: shared published locally (see README) and `sbt game-server/assembly`
#   - here: `sbt bffClient/luaLink`
set -euo pipefail

BFF_DIR="${BFF_DIR:-$(cd "$(dirname "$0")/../../BattleForFlatland" && pwd)}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"

stop_game_servers() {
  for pid in $(ps -eo pid,args | awk '$2 == "java" && /game-server\.jar/ {print $1}'); do kill "$pid" 2>/dev/null || true; done
}
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  stop_game_servers
  rm -rf "$WORK"
}
trap cleanup EXIT

# Classpaths of the menu server and the launcher (`server/assembly` would also build the frontend)
(cd "$BFF_DIR" && sbt -batch "export server/Runtime/fullClasspath" "export game-server-launcher/Runtime/fullClasspath") \
  | grep -E '\.jar' > "$WORK/classpaths"
SERVER_CP="$(sed -n 1p "$WORK/classpaths")"
LAUNCHER_CP="$(sed -n 2p "$WORK/classpaths")"

# BFF's game servers keep running after their game, and always use port 22222
stop_game_servers

(cd "$WORK" && exec java -DisProd=false -cp "$SERVER_CP" server.Server) > "$WORK/menu-server.log" 2>&1 &
pids+=($!)
(cd "$BFF_DIR" && exec java -cp "$LAUNCHER_CP" server.Server) > "$WORK/launcher.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do
  curl -s -o /dev/null http://127.0.0.1:9000/api/users/me && ss -ltn | grep -q ":22223" && break
  sleep 0.5
done
curl -s -o /dev/null http://127.0.0.1:9000/api/users/me || { tail "$WORK/menu-server.log"; exit 1; }

export BFF_MENU_URL=http://127.0.0.1:9000 BFF_USER="${BFF_USER:-alice}" BFF_CLASS="${BFF_CLASS:-Triangle}"
if [[ "${1:-}" == "--window" ]]; then
  love "$HERE/bff-client/target/lua"
else
  BFF_HEADLESS=1 timeout 120 love "$HERE/bff-client/target/lua"
fi
