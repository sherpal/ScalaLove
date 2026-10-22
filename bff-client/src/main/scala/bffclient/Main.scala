package bffclient

import java.nio.ByteBuffer

import boopickle.Default.*
import communication.BFFPicklers.*
import gamelogic.entities.Entity
import gamelogic.entities.classes.Constants
import gamelogic.gamestate.{ActionGatherer, GameAction, GameState, GreedyActionGatherer}
import gamelogic.gamestate.gameactions.MovingBodyMoves
import gamelogic.physics.Complex
import models.bff.ingame.InGameWSProtocol
import models.bff.ingame.InGameWSProtocol.*

/** Connects to a real Battle for Flatland game server, plays the start-of-game handshake like the
  * frontend, keeps a client-side copy of the game state from the server's updates, and renders it.
  *
  * Configuration via environment variables: BFF_HOST, BFF_PORT, BFF_USER, BFF_SECRET, BFF_GAME_ID.
  * With BFF_HEADLESS set, there is no window: the client walks a bit, logs, and quits at the end.
  */
object Main {
  def main(args: Array[String]): Unit = {
    def env(name: String, default: String): String = Option(Os.getenv(name)).getOrElse(default)
    val user     = env("BFF_USER", "alice")
    val headless = Os.getenv("BFF_HEADLESS") != null
    val url      = s"ws://${env("BFF_HOST", "localhost")}:${env("BFF_PORT", "22223")}/ws/connect" +
      s"?userName=$user&secret=${env("BFF_SECRET", "secret")}&gameId=${env("BFF_GAME_ID", "game")}"
    println(s"Connecting to $url")
    val connection = WebSocketModule.connect(url)
    if (connection.socket == null) {
      println(s"Connection failed: ${connection.error}")
      Event.quit(1)
    } else {
      println("Connected")
      val client = new GameClient(connection.socket, user, headless)
      Love.update = dt => client.update(dt)
      if (!headless) {
        val renderer = new Renderer(client)
        Love.draw = () => renderer.draw()
        Love.keypressed = (key, _, _) => if (key == "escape") client.quit()
      }
    }
  }
}

final class GameClient(ws: WebSocket, user: String, headless: Boolean) {
  private var gatherer: ActionGatherer = GreedyActionGatherer(GameState.empty)
  var myId: Option[Entity.Id]          = None
  var messagesReceived                 = 0
  var roundTrip                        = -1L
  private var elapsed                  = 0.0
  private var lastStatus               = 0.0
  private var pingCount                = 0
  private var actionsReceived          = 0
  private var bytesReceived            = 0L
  private var clockOffset              = 0L
  private var gameBeganAt: Option[Double] = None
  private var scriptedMovements        = 0
  private var lastInput: Option[Complex] = None

  send(Ready(user))

  def gameState: GameState = gatherer.currentGameState

  /** Estimate of the server's clock (from the ping/pong exchanges), used to interpolate positions. */
  def serverNow: Long = System.currentTimeMillis() + clockOffset

  private def send(message: InGameWSProtocol): Unit = {
    val buffer = Pickle.intoBytes[InGameWSProtocol](message)
    val bytes  = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    ws.send(ScalaLove.bytesToString(bytes))
  }

  private def sendMovement(direction: Complex, moving: Boolean): Unit =
    for (id <- myId; me <- gameState.players.get(id)) {
      val now      = serverNow
      val position = me.currentPosition(now)
      val angle    = if (moving) direction.arg else me.direction
      val rotation = if (moving) direction.arg else me.rotation
      send(GameActionWrapper(Vector(
        MovingBodyMoves(GameAction.Id.zero, now, id, position, angle, rotation, Constants.playerSpeed, moving)
      )))
    }

  def quit(): Unit = {
    ws.close()
    Event.quit(0)
  }

  def update(dt: Double): Unit = {
    elapsed += dt

    var data = ws.poll()
    while (data != null) {
      bytesReceived += data.length
      messagesReceived += 1
      handle(Unpickle[InGameWSProtocol].fromBytes(ByteBuffer.wrap(ScalaLove.stringToBytes(data))))
      data = ws.poll()
    }

    if (elapsed - lastStatus >= 1.0) {
      lastStatus = elapsed
      pingCount += 1
      send(Ping(System.currentTimeMillis(), s"ping-$pingCount"))
      if (headless) println(status)
    }

    if (gameBeganAt.isDefined && !gameState.ended) {
      if (headless) scriptedMovement() else keyboardMovement()
    }

    if (headless && (gameState.ended || elapsed > 60 || !ws.isOpen())) {
      println(status)
      println(
        s"Finished: game ended=${gameState.ended}, players won=${gameState.playersWon}, " +
          s"socket open=${ws.isOpen()}, $messagesReceived messages ($bytesReceived bytes), $actionsReceived actions"
      )
      quit()
    }
  }

  /** WASD: the direction is sent to the server when it changes (the screen's up is the world's +i). */
  private def keyboardMovement(): Unit = {
    var direction = Complex.zero
    if (Keyboard.isDown("w")) direction += Complex.i
    if (Keyboard.isDown("s")) direction -= Complex.i
    if (Keyboard.isDown("a")) direction -= Complex(1, 0)
    if (Keyboard.isDown("d")) direction += Complex(1, 0)
    val input = Option.when(direction != Complex.zero)(direction)
    if (input != lastInput) {
      lastInput = input
      sendMovement(input.getOrElse(Complex.zero), moving = input.isDefined)
    }
  }

  /** Two seconds after the start, walk towards the boss for one second. */
  private def scriptedMovement(): Unit =
    for (began <- gameBeganAt; boss <- gameState.bosses.values.headOption; id <- myId; me <- gameState.players.get(id)) {
      if (scriptedMovements == 0 && elapsed - began > 2) {
        val now = serverNow
        println(s"Sending a movement towards the boss from ${me.currentPosition(now)}")
        sendMovement(boss.currentPosition(now) - me.currentPosition(now), moving = true)
        scriptedMovements += 1
      } else if (scriptedMovements == 1 && elapsed - began > 3) {
        println(s"Sending a stop at ${me.currentPosition(serverNow)}")
        sendMovement(Complex.zero, moving = false)
        scriptedMovements += 1
      }
    }

  private def status: String = {
    val bosses = gameState.bosses.values.map(b => s"${b.name} life=${math.round(b.life)}").mkString(", ")
    val me = myId.flatMap(gameState.players.get).fold("-") { p =>
      val pos = p.currentPosition(serverNow)
      s"life=${math.round(p.life)} pos=(${math.round(pos.re)}, ${math.round(pos.im)})"
    }
    s"[t=${math.round(elapsed * 10) / 10.0}s] messages=$messagesReceived actions=$actionsReceived entities=${gameState.entities.size} " +
      s"boss: [$bosses] me: $me rtt=${roundTrip}ms started=${gameState.started} ended=${gameState.ended}"
  }

  private def handle(message: InGameWSProtocol): Unit = message match {
    case AllPlayers(names) =>
      println(s"<- AllPlayers(${names.mkString(", ")})")
    case BuddyIsReady(name) =>
      println(s"<- BuddyIsReady($name)")
      if (name == user) send(ReadyToStart(user))
    case EveryoneIsReady =>
      println("<- EveryoneIsReady, sending LetsBegin")
      send(LetsBegin)
      gameBeganAt = Some(elapsed)
    case YourEntityIdIs(id) =>
      println(s"<- YourEntityIdIs($id)")
      myId = Some(id)
    case StartingBossPosition(x, y) =>
      println(s"<- StartingBossPosition($x, $y)")
    case AddAndRemoveActions(toAdd, oldestTimeToRemove, idsToRemove) =>
      actionsReceived += toAdd.length
      gatherer = gatherer.slaveAddAndRemoveActions(toAdd, oldestTimeToRemove, idsToRemove)
    case RemoveActions(oldestTime, ids) =>
      gatherer = gatherer.slaveAddAndRemoveActions(Vector.empty, oldestTime, ids)
    case Pong(originalSendingTime, midwayDistantTime, _) =>
      val now = System.currentTimeMillis()
      roundTrip = now - originalSendingTime
      clockOffset = midwayDistantTime - (originalSendingTime + now) / 2
    case HeartBeat =>
      ()
    case other =>
      println(s"<- unexpected $other")
  }
}
