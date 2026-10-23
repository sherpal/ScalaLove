package selftest

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Facades for a few Lua standard functions returning multiple values. */
@js.native @JSGlobal("string")
object LuaString extends js.Object {
  def find(s: String, pattern: String): (Int, Int)                                  = js.native
  @JSName("find") def findNamed(s: String, pattern: String): (start: Int, end: Int) = js.native
  @JSName("find") def findAny(s: String, pattern: String): (Any, Any)               = js.native
  def byte(s: String, i: Int, j: Int): (Int, Int, Int)                              = js.native
  @JSName("byte") def byte4(s: String, i: Int, j: Int): (Int, Int, Int, Int)        = js.native
}

@js.native @JSGlobal("math")
object LuaMath extends js.Object {
  def modf(x: Double): (Double, Double) = js.native
}
