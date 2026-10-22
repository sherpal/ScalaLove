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

`sbt selftest/luaTest` runs a program checking ~70 language/library behaviours
(Int/Long overflow, strings, case classes, enums, collections, exceptions,
multiple return values, JSON with circe, ...).

To take screenshots from inside LÖVE (useful to check rendering from a script),
optionally simulating held keys:

```bash
love tools/screenshot game/target/lua 60,120 w:30-90,1@100
```

(`w:30-90` holds `w` from frame 30 to 90, `1@100` presses `1` at frame 100.)

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
`JSON.parse`/`JSON.stringify`, `instanceof`, ...); the runtime emulates those in `R.js`.

## Known limitations

* **Strings are byte strings**: `length`/`charAt` count bytes, not UTF-16 units.
  ASCII text behaves exactly like on the JVM; string literals are encoded in UTF-8.
* **No regular expressions**: `String.split` works for literal separators only
  (`split(',')`, `split(", ")`); `replaceAll`, `matches`, `String.format`, ... don't.
* Runtime checks are disabled (like Scala.js `fullLink`): no `ClassCastException`,
  `ArrayIndexOutOfBoundsException`, ... but `ArithmeticException` on `/ by zero` works.
* Not supported yet: returning multiple values *from* Scala callbacks to Lua, JS classes defined in Scala,
  dynamic imports, reflection beyond `getClass.getName`.
* LuaJIT only (LÖVE). Plain Lua 5.1 (World of Warcraft) needs `goto`-free control
  flow, a `Long` emulation (Scala.js' `RuntimeLong`) and a namespace table instead
  of globals.
