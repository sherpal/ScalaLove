#!/usr/bin/env bash
# Plays a one-player Boss 101 game against a real Battle for Flatland game server,
# with the LÖVE client of bff-client/: headless and scripted by default, or in a
# window with `--window` (WASD to move, Escape to quit).
#
# Prerequisites:
#   - in BattleForFlatland: shared published locally (see README) and `sbt game-server/assembly`
#   - here: `sbt bffClient/luaLink bffSimJVM/compile`
set -euo pipefail

BFF_DIR="${BFF_DIR:-$(cd "$(dirname "$0")/../../BattleForFlatland" && pwd)}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT=22223
READY_PORT=18081
GAME_ID=game-1
USER_NAME=alice
USER_SECRET=alice-secret

pids=()
cleanup() { for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; rm -rf "$WORK"; }
trap cleanup EXIT

# 1. The game info normally sent by the game server launcher
CP="$(cd "$HERE" && sbt -batch -error "export bffSimJVM/Runtime/fullClasspath" | tail -1)"
java -cp "$CP" bffsim.MakeGameInfo "$GAME_ID" game-secret "Boss 101" "$USER_NAME:Triangle:$USER_SECRET" > "$WORK/gameinfo"

# 2. The game server calls this URL once it listens
mkdir "$WORK/ready"
python3 -m http.server "$READY_PORT" --bind 127.0.0.1 --directory "$WORK/ready" > "$WORK/ready.log" 2>&1 &
pids+=($!)

# 3. The game server itself
(cd "$BFF_DIR" && exec java -jar game-server/target/game-server.jar \
  "http://127.0.0.1:$READY_PORT/" "$PORT" "$(cat "$WORK/gameinfo")") > "$WORK/game-server.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do grep -q "Server is warned" "$WORK/game-server.log" && break; sleep 0.5; done
grep -q "Server is warned" "$WORK/game-server.log" || { cat "$WORK/game-server.log"; exit 1; }

# 4. The Lua client: headless (scripted) by default, in a window with --window (WASD to move)
export BFF_HOST=127.0.0.1 BFF_PORT="$PORT" BFF_USER="$USER_NAME" BFF_SECRET="$USER_SECRET" BFF_GAME_ID="$GAME_ID"
if [[ "${1:-}" == "--window" ]]; then
  love "$HERE/bff-client/target/lua"
else
  BFF_HEADLESS=1 timeout 100 love "$HERE/bff-client/target/lua"
fi
