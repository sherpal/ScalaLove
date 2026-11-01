package scalalove.linker

/** The Lua dialect and environment a program is linked for. Its runtime prelude
  * (`prelude-<id>.lua`) provides what differs between them (see `runtime.lua`).
  */
enum LuaTarget(val id: String) {

  /** LuaJIT, as in LÖVE: `goto`, and Longs as 64-bit integers from its FFI. */
  case LuaJIT extends LuaTarget("luajit")

  /** Plain Lua 5.1 without `require`, FFI nor `io`, as in World of Warcraft: no `goto`, and Longs as
    * `{hi, lo}` tables.
    */
  case Lua51 extends LuaTarget("lua51")
}

object LuaTarget {
  def fromId(id: String): LuaTarget =
    values
      .find(_.id == id)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Unknown Lua target '$id' (expected one of ${values.map(_.id).mkString(", ")})"
        )
      )
}
