package love

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Facades for the LÖVE API. Each Lua module table (`love.graphics`, ...) is its own `@JSGlobal`
  * object: calls on it are emitted as `love.graphics.f(...)`, whereas calls on instances (e.g. an
  * `Image`) are emitted as `img:f(...)`.
  *
  * Lua functions returning several values are declared with a Scala tuple as result type,
  * preferably a named one, e.g. `def getDimensions(): (width: Int, height: Int)`.
  */
@js.native @JSGlobal("love")
object Love extends js.Object {
  var load: js.Function0[Unit]                                = js.native
  var update: js.Function1[Double, Unit]                      = js.native
  var draw: js.Function0[Unit]                                = js.native
  var keypressed: js.Function3[String, String, Boolean, Unit] = js.native
}

@js.native @JSGlobal("love.graphics")
object Graphics extends js.Object {
  def setColor(r: Double, g: Double, b: Double): Unit                                    = js.native
  def setColor(r: Double, g: Double, b: Double, a: Double): Unit                         = js.native
  def getColor(): (r: Double, g: Double, b: Double, a: Double)                           = js.native
  def rectangle(mode: String, x: Double, y: Double, width: Double, height: Double): Unit = js.native
  def circle(mode: String, x: Double, y: Double, radius: Double): Unit                   = js.native
  def line(x1: Double, y1: Double, x2: Double, y2: Double): Unit                         = js.native
  def print(text: String, x: Double, y: Double): Unit                                    = js.native
  def printf(text: String, x: Double, y: Double, limit: Double, align: String): Unit     = js.native
  def getWidth(): Int                                                                    = js.native
  def getHeight(): Int                                                                   = js.native
  def getDimensions(): (width: Int, height: Int)                                         = js.native
  def newFont(size: Int): Font                                                           = js.native
  def setFont(font: Font): Unit                                                          = js.native
}

@js.native
trait Font extends js.Object {
  def getHeight(): Double            = js.native
  def getWidth(text: String): Double = js.native
}

@js.native @JSGlobal("love.keyboard")
object Keyboard extends js.Object {
  def isDown(key: String): Boolean = js.native
}

@js.native @JSGlobal("love.mouse")
object Mouse extends js.Object {
  def getPosition(): (x: Double, y: Double) = js.native
  def isDown(button: Int): Boolean          = js.native
}

@js.native @JSGlobal("love.event")
object Event extends js.Object {
  def quit(): Unit = js.native
}
