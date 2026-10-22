package bffclient

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** The WebSocket client written in Lua (src/main/lua/websocket.lua). */
@js.native @JSImport("websocket", JSImport.Namespace)
object WebSocketModule extends js.Object {
  def connect(url: String): (socket: WebSocket, error: String)                                  = js.native
  def connect(url: String, timeout: Double, cookie: String): (socket: WebSocket, error: String) = js.native
}

/** Non-blocking HTTP (src/main/lua/http.lua). */
@js.native @JSImport("http", JSImport.Namespace)
object HttpModule extends js.Object {
  /** Returns the id of the request. */
  def request(method: String, url: String, body: String, cookie: String): Int = js.native

  /** The next response, or null. */
  def poll(): HttpResponse = js.native
}

@js.native
trait HttpResponse extends js.Object {
  val id: Int           = js.native
  /** 0 if the request failed (see `error`). */
  val status: Int       = js.native
  val body: String      = js.native
  val setCookie: String = js.native
  val error: String     = js.native
}

@js.native
trait WebSocket extends js.Object {
  /** Sends a binary message (a Lua string). */
  def send(data: String): Unit = js.native

  /** Next received message, or null. Never blocks. */
  def poll(): String = js.native

  def isOpen(): Boolean = js.native
  def close(): Unit     = js.native
}

/** Helpers of the ScalaLove runtime. */
@js.native @JSGlobal("scalalove")
object ScalaLove extends js.Object {
  def stringToBytes(s: String): Array[Byte]     = js.native
  def bytesToString(bytes: Array[Byte]): String = js.native
  /** A Lua sequence (1-based table, as returned by Lua functions) as a JS array. */
  def fromLuaSequence[A](sequence: LuaSequence[A]): js.Array[A] = js.native
  def toLuaSequence[A](array: js.Array[A]): LuaSequence[A]      = js.native
}

/** A Lua table with elements at indices 1 to n (convert it with `ScalaLove.fromLuaSequence`). */
@js.native
trait LuaSequence[A] extends js.Object

@js.native @JSGlobal("os")
object Os extends js.Object {
  def getenv(name: String): String = js.native
}

@js.native @JSGlobal("love")
object Love extends js.Object {
  var update: js.Function1[Double, Unit]                      = js.native
  var draw: js.Function0[Unit]                                = js.native
  /** (key, scancode, isRepeat) */
  var keypressed: js.Function3[String, String, Boolean, Unit] = js.native
  /** (key, scancode) */
  var keyreleased: js.Function2[String, String, Unit]         = js.native
  /** (x, y, button, isTouch, presses) */
  var mousepressed: js.Function5[Double, Double, Int, Boolean, Int, Unit] = js.native
  /** (x, y, button, isTouch, presses) */
  var mousereleased: js.Function5[Double, Double, Int, Boolean, Int, Unit] = js.native
  /** Text typed on the keyboard (UTF-8), see `love.keyboard.setTextInput`. */
  var textinput: js.Function1[String, Unit]                   = js.native
  /** Not null when the graphics module is enabled (i.e. not headless). */
  val graphics: js.Any = js.native
}

@js.native @JSGlobal("love.graphics")
object Graphics extends js.Object {
  def setBackgroundColor(r: Double, g: Double, b: Double): Unit                       = js.native
  def setColor(r: Double, g: Double, b: Double, a: Double = 1.0): Unit                = js.native
  def setLineWidth(width: Double): Unit                                               = js.native
  def rectangle(mode: String, x: Double, y: Double, width: Double, height: Double): Unit = js.native
  /** With rounded corners (radii rx, ry). */
  def rectangle(mode: String, x: Double, y: Double, width: Double, height: Double, rx: Double, ry: Double): Unit =
    js.native
  def circle(mode: String, x: Double, y: Double, radius: Double): Unit                = js.native
  def line(x1: Double, y1: Double, x2: Double, y2: Double): Unit                      = js.native
  /** `coords` are x1, y1, x2, y2, ... (filled polygons must be convex). */
  def polygon(mode: String, coords: Double*): Unit                                    = js.native
  def print(text: String, x: Double, y: Double): Unit                                 = js.native
  def printf(text: String, x: Double, y: Double, limit: Double, align: String): Unit  = js.native
  def getDimensions(): (width: Int, height: Int)                                      = js.native
  /** Draws the image at (x, y), turned by `r` (clockwise), scaled, with origin (ox, oy) in the image. */
  def draw(image: Image, x: Double, y: Double, r: Double, sx: Double, sy: Double, ox: Double, oy: Double): Unit =
    js.native
  /** Draws the part `quad` of the image. */
  def draw(image: Image, quad: Quad, x: Double, y: Double, r: Double, sx: Double, sy: Double, ox: Double,
      oy: Double): Unit = js.native
  def newQuad(x: Double, y: Double, width: Double, height: Double, imageWidth: Double, imageHeight: Double): Quad =
    js.native
  def newFont(size: Int): Font                                                        = js.native
  def setFont(font: Font): Unit                                                       = js.native
  def getFont(): Font                                                                 = js.native
}

/** Images, from src/main/lua/images.lua. */
@js.native @JSImport("images", JSImport.Namespace)
object Images extends js.Object {
  /** The image at this path of the game directory, or null if there is none. */
  def load(path: String): Image = js.native
}

@js.native
trait Image extends js.Object {
  def getWidth(): Double  = js.native
  def getHeight(): Double = js.native
}

/** A part of an image. */
@js.native
trait Quad extends js.Object

@js.native
trait Font extends js.Object {
  def getWidth(text: String): Double = js.native
  def getHeight(): Double            = js.native
  /** The lines of `text` wrapped at `limit` pixels. */
  def getWrap(text: String, limit: Double): (width: Double, lines: LuaSequence[String]) = js.native
}

@js.native @JSGlobal("love.mouse")
object Mouse extends js.Object {
  def getPosition(): (x: Double, y: Double) = js.native
  def isDown(button: Int): Boolean          = js.native
}

@js.native @JSGlobal("love.keyboard")
object Keyboard extends js.Object {
  def isDown(key: String): Boolean              = js.native
  def isScancodeDown(scancode: String): Boolean = js.native
  def setKeyRepeat(enable: Boolean): Unit       = js.native
}

@js.native @JSGlobal("love.timer")
object Timer extends js.Object {
  /** Seconds since some unspecified time. */
  def getTime(): Double = js.native
}

@js.native @JSGlobal("love.event")
object Event extends js.Object {
  def quit(exitStatus: Int): Unit = js.native
}

@js.native @JSGlobal("love.filesystem")
object Filesystem extends js.Object {
  /** Null if the file does not exist. */
  def getInfo(path: String): js.Any                                         = js.native
  def read(path: String): (contents: String, size: Any)                     = js.native
  def write(path: String, data: String): (success: Boolean, message: String) = js.native
  def getSaveDirectory(): String                                            = js.native
}
