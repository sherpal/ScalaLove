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
  var update: js.Function1[Double, Unit] = js.native
}

@js.native @JSGlobal("love.event")
object Event extends js.Object {
  def quit(exitStatus: Int): Unit = js.native
}
