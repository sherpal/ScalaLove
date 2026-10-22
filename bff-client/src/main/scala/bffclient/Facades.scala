package bffclient

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** The WebSocket client written in Lua (src/main/lua/websocket.lua). */
@js.native @JSImport("websocket", JSImport.Namespace)
object WebSocketModule extends js.Object {
  def connect(url: String): (socket: WebSocket, error: String) = js.native
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
}

@js.native @JSGlobal("os")
object Os extends js.Object {
  def getenv(name: String): String = js.native
}

@js.native @JSGlobal("love")
object Love extends js.Object {
  var update: js.Function1[Double, Unit]                      = js.native
  var draw: js.Function0[Unit]                                = js.native
  var keypressed: js.Function3[String, String, Boolean, Unit] = js.native
  /** Not null when the graphics module is enabled (i.e. not headless). */
  val graphics: js.Any = js.native
}

@js.native @JSGlobal("love.graphics")
object Graphics extends js.Object {
  def setBackgroundColor(r: Double, g: Double, b: Double): Unit                       = js.native
  def setColor(r: Double, g: Double, b: Double, a: Double = 1.0): Unit                = js.native
  def setLineWidth(width: Double): Unit                                               = js.native
  def rectangle(mode: String, x: Double, y: Double, width: Double, height: Double): Unit = js.native
  def circle(mode: String, x: Double, y: Double, radius: Double): Unit                = js.native
  def line(x1: Double, y1: Double, x2: Double, y2: Double): Unit                      = js.native
  /** `coords` are x1, y1, x2, y2, ... (filled polygons must be convex). */
  def polygon(mode: String, coords: Double*): Unit                                    = js.native
  def print(text: String, x: Double, y: Double): Unit                                 = js.native
  def printf(text: String, x: Double, y: Double, limit: Double, align: String): Unit  = js.native
  def getDimensions(): (width: Int, height: Int)                                      = js.native
}

@js.native @JSGlobal("love.keyboard")
object Keyboard extends js.Object {
  def isDown(key: String): Boolean = js.native
}

@js.native @JSGlobal("love.event")
object Event extends js.Object {
  def quit(exitStatus: Int): Unit = js.native
}
