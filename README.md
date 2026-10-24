# ScalaLove

A proof of concept of a Scala → Lua compiler targeting [LÖVE](https://love2d.org/)
(LuaJIT), built on top of the Scala.js toolchain.

```scala
object Main {
  def main(args: Array[String]): Unit = {
    val game = new Pong(Graphics.getWidth().toDouble, Graphics.getHeight().toDouble)
    Love.update = dt => game.update(dt)
    Love.draw = () => game.draw()
  }
}
```

## Try it

Requirements: JDK 17+, sbt, `love` (11.x) and `luajit` on the `PATH`.

```bash
sbt game/luaRun
```

This compiles the Pong game in `game/`, links it to `game/target/lua/main.lua` and
opens it in LÖVE. Controls: `W`/`S` move the left paddle, `Escape` quits.

Other tasks (available on every Lua project):

| Task          | What it does                                                        |
|---------------|---------------------------------------------------------------------|
| `luaLink`     | Writes `target/lua/main.lua`                                        |
| `luaRun`      | `luaLink`, then runs the result with `love`                         |
| `luaTest`     | `luaLink`, then runs it headlessly with `luajit` (fake `love` table)|
| `luaProbe`    | Prints the linked classes and IR node statistics (debugging)        |

`sbt selftest/luaTest` runs a program checking ~85 language/library behaviours
(Int/Long overflow, strings, case classes, enums, collections, exceptions,
multiple return values, JSON with circe, regular expressions, `String.format`,
futures and timers, ...).

To take screenshots from inside LÖVE (useful to check rendering from a script),
optionally simulating input:

```bash
love tools/screenshot game/target/lua 60,120 w:30-90,1@100
```

(`w:30-90` holds `w` from frame 30 to 90, `1@100` presses `1` at frame 100,
`click@50:400:300` clicks at (400, 300) at frame 50, `mouse@40:400:300` moves
the mouse there, `button@60:2` presses the right mouse button, `wheel@70:-3`
turns the mouse wheel, `text@20:hello` types
`hello` at frame 20. `SCREENSHOT_IDENTITY` sets the save directory, to run
several instances at once.)

## Experiment: Battle for Flatland's game logic

`bff-sim/` runs the game logic of
[Battle for Flatland](../BattleForFlatland) (its `shared` module, consumed as a
library, not copied) in a headless, deterministic replay of the game server's
loop: simulated clock at 125 Hz, `GreedyActionGatherer` + the `ServerAction`s,
boss from `BossFactory`, and scripted players walking to the boss and using
their main ability. The same driver runs on the JVM (`bffSimJVM`) and in Lua
(`bffSim`), so the outputs can be compared.

```bash
# In BattleForFlatland: publish the shared module locally (no file is modified)
sbt 'set ThisBuild / organization := "local.bff"' 'set ThisBuild / version := "0.1.0-local"' \
    'set LocalProject("sharedJS") / Compile / packageDoc / publishArtifact := false' \
    'set LocalProject("sharedJVM") / Compile / packageDoc / publishArtifact := false' \
    sharedJS/publishLocal sharedJVM/publishLocal

# Here
sbt bffSimJVM/run bffSim/luaTest
```

Results (Boss 101, 8 players, ~11k ticks until the boss dies):

* The Lua output is identical to the JVM one (positions, lives, buffs, action
  counts, end tick), once doubles are printed the Scala.js way (`20000` vs `20000.0`).
* 1,670 classes, ~99k lines of Lua.
* ~2.5 s in LuaJIT (≈0.23 ms per tick), vs ~0.7 s on the JVM including its start-up.

The websocket traffic of that fight (485 messages: the server's
`AddAndRemoveActions`, the clients' `GameActionWrapper`s and the other
`InGameWSProtocol` messages) is then round-tripped through boopickle with the
game's own `BFFPicklers`:

* The bytes produced in Lua are identical to the JVM ones (same checksum over
  the 53,024 bytes), and every message decodes back to an equal value. A Lua
  client can therefore talk to the existing JVM game server as is.
* Encoding + decoding takes ≈0.16 ms per message in LuaJIT once warm
  (≈0.01 ms on the JVM).

### Playing against the real game server

`bff-client/` is a headless LÖVE client written in Scala that connects to an
actual Battle for Flatland game server (BFF's `game-server.jar`, unchanged):

* WebSocket: a small RFC 6455 client in Lua on top of LuaSocket (bundled with
  LÖVE), `bff-client/src/main/lua/websocket.lua`, bound with
  `@JSImport("websocket", JSImport.Namespace)` (compiled to `require("websocket")`).
* It plays the frontend's start-of-game handshake (`Ready`, `ReadyToStart`,
  `LetsBegin`), keeps a client-side game state with the game's own
  `GreedyActionGatherer.slaveAddAndRemoveActions`, pings the server, and sends a
  movement through the server.

```bash
# In BattleForFlatland: publish shared (see above), then
sbt game-server/assembly
# Here
sbt bffClient/luaLink bffSimJVM/compile
tools/bff-online-test.sh
```

The client's code (`bff-client/src/main/scala/bffclient`) is organised by
package: `Main` starts the menus or the game; `game` is the client side of a
game (`GameClient`, `AbilityCaster`); `render` draws it (`Renderer`, `Painter`,
`Sprites`, `Effects`, `Smoothing`, and the bosses' visuals in `render.bosses`);
`hud` draws the HUD and the overlays (game menu, end of game); `controls` loads,
saves and edits the controls; `menu` holds the menus' screens and the menu
server's API; `ui` is the UI toolkit; `love` holds the facades of LÖVE's API
(with `LoveInput`), and `lua` those of our own Lua modules (`src/main/lua`).

The script starts the game server with a one-player Boss 101 game (the game
info normally sent by the launcher is built by `bffsim.MakeGameInfo`), runs the
client, and stops everything. With `tools/bff-online-test.sh --window`, the
client opens a window and renders the game state with `love.graphics`
(`bff-client/.../Renderer.scala`, modelled on the Indigo drawers: camera on the
player, obstacles, boss, players in their colours, other bodies as outlines,
life and casting bars, game markers, a small HUD), and you can play with the
game's own `Controls` (its default bindings: WASD to move, digits for the
abilities, Tab for the next target, CapsLock to face the target, R/F/V/T/G/B
for markers, with Shift at the mouse position). LÖVE scancodes are converted to
the DOM key codes used by `Controls` (`bff-client/.../LoveInput.scala`), then to
`UserInput`s, handled like in the frontend: abilities as in
`CastAbilitiesHandler` (same legality checks), next target as in
`NextTargetHandler`, markers sent as `UpdateMarker`. A click selects a target
or places a zone.

The controls are read from `controls.json` in LÖVE's save directory
(`~/.local/share/love/scalalove-bff/` on Linux), written with the default
controls on the first run. It accepts the JSON of a `Controls`, or what the
browser frontend stores in its local storage under the key `controls`
(`{"element": ..., "timestamp": ...}`), so custom bindings can be copied from
the browser. Decoding uses circe, whose Scala.js parser calls `JSON.parse`: the
runtime emulates it (objects keep their key order and `null` values). The headless run also casts Direct Hit on the boss through
the server. Result: the handshake completes, the boss's AI
kills our idle triangle in ~13 s (as seen from the client-side game state), our
movement is applied and echoed by the server, and the ping round trip is 2-4 ms.

Our own movement is predicted, like the frontend does: every frame, the client
moves its player itself (stopping at obstacles), sends the new position, and
shows the server's game state with that movement applied on top, instead of
waiting one round trip for the server's echo. Positions come from the frame's
duration, so the speed does not depend on the frame rate (BFF's
`lastValidPosition` ignores moves under 2 units, which happen above ~75 fps:
small steps are checked against the obstacles directly). Movements are sent
when starting, stopping, turning or changing direction, and about 60 times per
second while moving.

The other entities (players, bosses, adds, projectiles) are extrapolated from
the last movement received, so they jump when a turn or a stop arrives (one
round trip late). Those jumps are smoothed when drawing (`Smoothing.scala`): a
jump becomes an offset from the extrapolated position, which decays in about
100 ms. Steady movements are not delayed, and jumps over 100 units (teleports)
are shown as they are. Rotations are not extrapolated, so they snapped to each
new value: the shown rotation now turns towards the actual one by the shortest
way, in about 60 ms (this one delays turns a little). The game logic keeps
using the actual positions and rotations. With 300 ms of simulated latency, the
boss's largest jump between two frames goes from 53 units to 4, and its largest
turn from 3.14 rad (a half turn) to 0.16 (headless runs report both).

The players are drawn with the frontend's class sprites (white shapes tinted
with the player's colour), with the frontend's sparks in their hands while
they cast. Like the frontend's `EffectsManager`, the abilities have effects
(`Effects.scala`): damage numbers (red for the damage we take, white for the
damage we deal), green numbers for our heals, the square's cleave and the swing
of the triangle's direct hit. Each action has its effects once, even when the
server sends it again. The numbers follow their entity. Hexagon and pentagon
zones are drawn in their owner's colour, and pentagon bullets too. The action
bar shows the frontend's ability icons, darkened from the top during their
cooldown and pushed down for a moment when used. Above each player, boss and add, the icons
of their buffs and debuffs (the frontend's, oldest first), darkened by the part
of their duration that remains. The permanent buffs that every player of a class
has (the square's shield, the resource fillers) are not shown.

The HUD (`Hud.scala`, drawn in the style of `HudStyle.scala`: dark translucent
panels with rounded corners, rounded bars on a dark track, white text with a
shadow) has the frontend's components: the frames of the team on
the left (class, name, life, resource and buffs; faded when out of healing
range; outlined in red when targeted), our frame and our target's above the
action bar (with what the target casts, and a lock when we face it), our cast
bar above them, the boss's frame on top (life, and what it casts), the
cooldowns of the boss's abilities on the top right, and the threat meter on the
bottom right. Clicking the frame of a player or of the boss
targets it (so that healers can target their team). With "mouse over" enabled in
the controls (`mouseOverEnabled`, the frontend's option), the abilities with a
target fall back to the entity whose frame is under the mouse when there is no
target or the ability cannot be cast on it (e.g. heal the team while keeping the
boss as target). The frame under the mouse is highlighted. Like the frontend's boss
plugins, Boss 104 and Boss 105 tint the frames of the players with a twin
debuff or a volatile charge, and Boss 102 counts its hounds.

What is specific to each boss is drawn like the frontend's
`bossspecificdrawers`, one `BossVisuals` per boss (package `render.bosses`): Boss
102's hounds, damage zones
and living damage zones (animated); Boss 104's big guys, death marks, debuff
circles and the finisher's triangle; Boss 105's Shade, the twins' auras and
bond, its ring, the Skewer's beam, the volatile charges, the reformation points,
Siphon and Reunion; Sir of Vice's Sacred Ground, Cleansing Nova and Holy Wrath.
Like the frontend's `drawerMapping`, the visuals are chosen by boss name (and
kept once the boss died, for its adds); Boss 101 and Boss 110 have none.

The images are the frontend's: `bffClient/luaLink` copies them from the Battle
for Flatland checkout (`BFF_DIR`, by default `../BattleForFlatland`) next to
`main.lua`, they are not copied in this repository. Without them, the client
draws shapes instead.

To try it with latency, `BFF_LAG_MS` simulates a round trip (half on each way),
e.g. `BFF_LAG_MS=300 tools/bff-menu-test.sh --window`. Headless, the status
lines show our predicted position next to the server's.

### Through the menu server

In a window, the client first goes through BFF's menu server, over HTTP and the
lobby websocket, like the browser frontend, with the game's own request/response
types and circe codecs (`MenuApi.scala`). Headless, it does so with
`BFF_MENU_URL` (with `BFF_HOST`, it connects directly to a game server).

The menus have a UI drawn with love.graphics, replacing the frontend's Laminar
pages (package `menu`, one file per screen):

* **login**: type the menu server's address (`host` or `host:port`, port 9000
  by default; `BFF_MENU_URL` fills it in) and choose a pseudo (validated with
  BFF's `LoginUser.validator`);
* **games**: the games that did not start, refreshed live from the lobby
  websocket; create one, or join one (or join back the one you are in);
* **game lobby**: the players with their class, colour and readiness; choose
  your class and colour, get ready; the creator chooses the boss (with its
  documentation from `BossMetadata`), kicks players, adds AIs and launches the
  game once `GameConfiguration.validate` has nothing to say. The game server
  credentials then arrive on the lobby websocket, and the game starts.

The server, pseudo, class, colour and boss are remembered in `menu.json` in LÖVE's save
directory (the frontend keeps them in its local storage).

In the lobby, the panel on the right documents the boss and the classes (life,
resource, description, and each ability with its icon, cast time, cooldown and
cost), like the frontend's `GameDocs`; it scrolls with the mouse wheel. The
"Controls" button (also in the games' list) opens the controls, like the
frontend's `ControlSettings`: click a binding, then press the key (possibly with
Shift) or the mouse button to bind to it; keys bound twice are pointed out, the
mouse over can be toggled, and everything can be reset. They are saved in
`controls.json`, for the next game (mouse buttons are numbered like in the
frontend, so the controls of its local storage still work).

Once every player is ready, they can move and place themselves before the boss
comes: like in the frontend, a "Start game" button is drawn where the boss
spawns. Clicking it starts a 5 seconds countdown, which every player sees. BFF
has no countdown (its game server spawns the boss when a player sends
`LetsBegin`), so its start is shared as a Star marker fixed exactly on the
boss's spawn point (which nobody can place by hand), the time of its action
being the start of the countdown. At its end, every client sends `LetsBegin`
(the first one spawns the boss, the others are ignored), and the marker is
removed. Headless, the client starts the countdown right away.

The window can be resized, and F11 (or the game menu) makes it fullscreen. The
world is scaled to show as much of it as in the original 1200 x 800 window (more
in the longer direction), while the HUD and the menus keep their size, at the
window's edges.

During the game, Escape opens a menu with "Resume" (or Escape again),
"Surrender", "Fullscreen", "Leave game" and "Quit". The game cannot pause (it is played
online): it goes on, and the player stops moving. Surrendering (confirmed with a
second click) ends the fight in a defeat for everyone: BFF has no surrender,
but its game server takes the players' actions as they are, so the client sends
the game's own `EndGame` action, and the end of the game arrives like any other
(the frontend's "stop" button kills the game server instead, which the players
would see as a lost connection). Leaving goes back to the menus; the game's server is
stopped only if we are its only human player, so that the others go on playing.

When the game ends (or the game server closes the connection), an overlay shows
the outcome, with "Back to menus" (Enter) and "Quit" (Escape). Going back stops
the game's server through the menu server (like the frontend's "stop" button:
BFF's game servers do not stop by themselves after their game, and all use port
22222), leaves the game (the menu server never ends games by itself, so the
players would otherwise stay "already playing" until it notices that they left
the lobby websocket), and shows the games again.

The UI toolkit (`ui/Ui.scala`) is a small immediate mode UI: every frame, the
screens call widgets that draw themselves and return what happened, e.g.
`if ui.button("Join", rect) then join()`. It has labels, wrapped text, panels,
buttons, text fields (UTF-8 aware, with keyboard focus), check boxes,
`< value >` selectors, segmented buttons and colour swatches. Immediate mode fits
LÖVE's draw loop and needs little code; there is no layout engine, screens
position widgets with `Rect`s.

Headless, the menus are scripted (`MenuSession.scala`): log in, create a game,
pick `BFF_CLASS` and get ready, choose `BFF_BOSS`, start, play.

HTTP requests run on a LÖVE thread (`http.lua`, `http_worker.lua`, LuaSocket),
so the game loop never blocks. `HttpClient` returns `Future`s, so the scripted
flow is a for-comprehension.

```bash
tools/bff-menu-test.sh            # headless, scripted menus
tools/bff-menu-test.sh --window   # with the menus' UI
# Through tools/screenshot: log in as alice and create a game
tools/bff-menu-test.sh --screenshot 50,100 "text@10:alice,return@20,click@70:445:125"
```

The script runs the menu server (from a temporary directory, where it keeps its
JSON files) and the game server launcher from their sbt classpaths, then stops
everything. Note that BFF's game servers keep running after their game and
always use port 22222, so a second game needs the first game server to be
stopped: the client does it when going back to the menus, and the script stops
the leftovers.

### Playing with friends

`sbt bffClient/luaPackage` packages the client with LÖVE's own release builds,
so that players do not need to install LÖVE (see
[Game Distribution](https://love2d.org/wiki/Game_Distribution)). It links the
client, zips it into `bff-client/target/dist/BattleForFlatland.love`, and writes
next to it:

* `BattleForFlatland-windows.zip`: `BattleForFlatland.exe` (`love.exe` with the
  game appended, which LÖVE runs) and LÖVE's DLLs;
* `BattleForFlatland-macos.zip`: `Battle for Flatland.app` (LÖVE's `love.app`
  with the game in its resources, and its own name and identifier);
* `BattleForFlatland-linux.tar.gz`: LÖVE's AppImage, the game, and a
  `BattleForFlatland` launcher (which runs the AppImage without FUSE, often not
  installed nowadays).

`luaPackage` works for any project compiled to Lua (`project/LovePackage.scala`;
`luaPackageTitle` names the game). LÖVE's builds (11.5) are downloaded once into
`target/love-11.5`, and their archives are copied entry by entry (with
commons-compress), keeping the Unix modes and the symbolic links of the macOS
app. `BFF_CLIENT=<launcher> tools/bff-menu-test.sh` plays a game with the Linux
package.

The packages are not signed: on Windows, SmartScreen asks to confirm ("More
info", then "Run anyway"); on macOS, the app must be unquarantined and signed
again (the game added to LÖVE's app breaks its signature), in a terminal, once:

```bash
xattr -cr "Battle for Flatland.app"
codesign --force --deep --sign - "Battle for Flatland.app"
```

To host the game, run BFF's menu server and game server launcher as usual
(`server/reStart` listens on all interfaces; not with `-DisProd=false`, which
only listens on 127.0.0.1), and forward TCP ports **9000** (menu server) and
**22222** (game servers) of the router to that machine (and open them in its
firewall, if any). The launcher's port (22223) stays private. Players then
type the router's public address on the login screen, e.g. `81.12.34.56`
(port 9000 by default); the game server is reached at the same address. When
the address changes, they type the new one (or use a dynamic DNS name: host
names work too). From the hosting machine itself, use `127.0.0.1`. BFF has no
passwords: anyone with the address can log in, with any pseudo.

The connection is plain HTTP and websockets (LuaSocket has no TLS), like the
frontend talking to a local server.

## How it works

```
Scala sources ──scalac + Scala.js plugin──▶ .sjsir (Scala.js IR)
                                              │
                       Scala.js linker frontend (unchanged): loading,
                       reachability analysis, dead code elimination
                                              │
                                              ▼
                              linker/ : Lua emitter ──▶ main.lua
```

* `linker/` is a plain JVM project depending on `scalajs-linker`. It runs the
  standard Scala.js frontend (optimizer disabled) and replaces the JavaScript
  backend with a Lua emitter:
  * `LuaEmitter.scala`: whole program layout (class tables, inheritance, runtime hooks, main).
  * `FunctionEmitter.scala`: IR trees → Lua statements/expressions.
  * `Naming.scala`: IR names → Lua identifiers.
  * `resources/scalalove/runtime.lua`: the runtime library, prepended to the output.
* Only reachable code is emitted: Pong links 211 classes (~5.7k lines of Lua).

### Value representation

| Scala                       | Lua                                                            |
|-----------------------------|----------------------------------------------------------------|
| `Int`, `Short`, `Byte`      | number, wrapped to 32 bits with `bit.tobit`                    |
| `Double`, `Float`           | number (`Float` rounded through the FFI)                       |
| `Long`                      | LuaJIT `int64_t` cdata (native 64-bit wraparound)              |
| `Char`                      | number (UTF-16 code unit); boxed as `{c = ...}` when upcast    |
| `Boolean`                   | boolean                                                        |
| `String`                    | Lua string (**byte** semantics for now)                        |
| `null`, `()`                | `nil`                                                          |
| objects                     | tables whose metatable is the class table (methods copied down)|
| arrays, `js.Array`          | 0-based tables with an explicit `length` field                 |

Control flow: labeled blocks and early returns use `goto` (LuaJIT supports it),
`try`/`catch`/`finally` use `pcall`.

Performance notes (LuaJIT):

* Scala lambdas are lifted to top-level functions; a lambda value is a table
  `{F.fn, captures...}`. LuaJIT cannot compile closure creation (`FNEW`), which
  otherwise aborts most traces in collection-heavy code.
* The runtime raises LuaJIT's machine-code and trace limits (`jit.opt.start`):
  with the defaults, big programs make LuaJIT flush and recompile its traces
  endlessly (10× slower on the Battle for Flatland experiment).
* Lua allows 200 locals per function: huge methods (e.g. inlined Magnolia
  derivations) store their locals in a table instead.

Futures and timers: the runtime emulates the JS event loop (`setTimeout`,
`setInterval`, `queueMicrotask`), which Scala.js' global `ExecutionContext` uses.
In LÖVE, the pending tasks run at the start of every frame (`love.update` is
wrapped), so `Future`s and `js.timers` work as in the browser; elsewhere, call
`scalalove.runTimers()`.

Lua modules: `@JSImport("mod", ...)` facades are compiled to `require("mod")`,
and the `.lua` files of a project's `src/main/lua` are copied next to `main.lua`
by `luaLink`. The runtime also exposes a `scalalove` global with
`stringToBytes`/`bytesToString` to convert between Lua (binary) strings and
Scala `Array[Byte]`.

### Calling LÖVE

LÖVE is called through ordinary Scala.js facades (see `game/src/main/scala/love/Love.scala`):

* A `@JSGlobal("love.graphics") object Graphics extends js.Object` is a Lua module:
  its methods are called with a dot, `love.graphics.rectangle(...)`.
* Other JS-typed values (e.g. a `Font` returned by `newFont`) are objects: their
  methods are called with a colon, `font:getWidth(...)`.
* Lua sequences (1-based tables, e.g. the lines returned by `Font:getWrap`) are
  not `js.Array`s (0-based, with a `length`): `scalalove.fromLuaSequence` and
  `toLuaSequence` convert them.
* Scala functions assigned to `js.Function` fields become plain Lua functions,
  so `Love.update = dt => ...` is `love.update = function(dt) ... end`.
* Lua functions returning several values are declared with a Scala tuple
  (preferably a named tuple) as result type. All the returned values are packed
  into the tuple (missing ones are `null`, i.e. `0` for numeric elements):

  ```scala
  @js.native @JSGlobal("love.graphics")
  object Graphics extends js.Object {
    def getDimensions(): (width: Int, height: Int) = js.native
  }

  val screen = Graphics.getDimensions()
  screen.width                                   // by name
  val (width, height) = Graphics.getDimensions() // or by position
  ```

  This works with any Lua function (e.g. `string.find`) and tuples up to `Tuple22`.

The linker frontend is configured for ECMAScript 5.1, which makes the Scala.js
library pick its pure Scala fallbacks over newer JS built-ins (e.g. heap
`ByteBuffer`s instead of typed arrays).

The Scala.js standard library itself calls a few JavaScript built-ins
(`Math.max`, `Object.prototype.hasOwnProperty`, JS arrays, string methods,
`JSON.parse`/`JSON.stringify`, `instanceof`, `RegExp`, ...); the runtime emulates those in
`R.js`. Regular expressions use a small backtracking engine for the JS syntax that the Scala.js
regex compiler produces (`java.util.regex`, `String.format`, `split`, `replaceAll`, ...).

## Known limitations

* **Strings are byte strings**: `length`/`charAt` count bytes, not UTF-16 units.
  ASCII text behaves exactly like on the JVM; string literals are encoded in UTF-8.
* **Regular expressions** work on bytes: classes like `\w` or `[a-z]` are about
  ASCII, and characters outside Latin-1 never match.
* Runtime checks are disabled (like Scala.js `fullLink`): no `ClassCastException`,
  `ArrayIndexOutOfBoundsException`, ... but `ArithmeticException` on `/ by zero` works.
* Not supported yet: returning multiple values *from* Scala callbacks to Lua, JS classes defined in Scala,
  dynamic imports, reflection beyond `getClass.getName`.
* LuaJIT only (LÖVE). Plain Lua 5.1 (World of Warcraft) needs `goto`-free control
  flow, a `Long` emulation (Scala.js' `RuntimeLong`) and a namespace table instead
  of globals.
