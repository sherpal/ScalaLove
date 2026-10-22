package bffclient

import java.nio.ByteBuffer

import boopickle.Default.*
import communication.BFFPicklers.*
import gamelogic.entities.Entity
import gamelogic.entities.classes.Constants
import gamelogic.gamestate.{ActionGatherer, GameAction, GameState, GreedyActionGatherer}
import gamelogic.gamestate.gameactions.MovingBodyMoves
import models.bff.ingame.InGameWSProtocol
import models.bff.ingame.InGameWSProtocol.*

/** Connects to a real Battle for Flatland game server, plays the start-of-game handshake like the
  * frontend, then keeps a client-side copy of the game state from the server's updates.
  *
  * Configuration via environment variables: BFF_HOST, BFF_PORT, BFF_USER, BFF_SECRET, BFF_GAME_ID.
  */
object Main {
  def main(args: Array[String]): Unit = {
    def env(name: String, default: String): String = Option(Os.getenv(name)).getOrElse(default)
    val user   = env("BFF_USER", "alice")
    val url    = s"ws://${env("BFF_HOST", "localhost")}:${env("BFF_PORT", "22223")}/ws/connect" +
      s"?userName=$user&secret=${env("BFF_SECRET", "secret")}&gameId=${env("BFF_GAME_ID", "game")}"
    println(s"Connecting to $url")
    val connection = WebSocketModule.connect(url)
    if (connection.socket == null) {
      println(s"Connection failed: ${connection.error}")
      Event.quit(1)
    } else {
      println("Connected")
      val client = new GameClient(connection.socket, user)
      Love.update = dt => client.update(dt)
    }
  }
}

final class GameClient(ws: WebSocket, user: String) {
  private var gatherer: ActionGatherer = GreedyActionGatherer(GameState.empty)
  private var myId: Option[Entity.Id]  = None
  private var elapsed                  = 0.0
  private var lastStatus               = 0.0
  private var pingCount                = 0
  private var messagesReceived         = 0
  private var actionsReceived          = 0
  private var bytesReceived            = 0L
  private var lastRoundTrip            = -1L
  private var gameBeganAt: Option[Double] = None
  private var movementsSent            = 0

  send(Ready(user))

  private def send(message: InGameWSProtocol): Unit = {
    val buffer = Pickle.intoBytes[InGameWSProtocol](message)
    val bytes  = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    ws.send(ScalaLove.bytesToString(bytes))
  }

  private def gameState: GameState = gatherer.currentGameState

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
      println(status)
    }

    // Two seconds after the start, walk towards the boss for one second, through the server
    for (began <- gameBeganAt; id <- myId; me <- gameState.players.get(id); boss <- gameState.bosses.values.headOption) {
      val now = System.currentTimeMillis()
      if (movementsSent == 0 && elapsed - began > 2) {
        val myPos     = me.currentPosition(now)
        val direction = (boss.currentPosition(now) - myPos).arg
        println(s"Sending a movement towards the boss from $myPos")
        send(GameActionWrapper(Vector(
          MovingBodyMoves(GameAction.Id.zero, now, id, myPos, direction, direction, Constants.playerSpeed, moving = true)
        )))
        movementsSent += 1
      } else if (movementsSent == 1 && elapsed - began > 3) {
        val myPos = me.currentPosition(now)
        println(s"Sending a stop at $myPos")
        send(GameActionWrapper(Vector(
          MovingBodyMoves(GameAction.Id.zero, now, id, myPos, me.direction, me.rotation, Constants.playerSpeed, moving = false)
        )))
        movementsSent += 1
      }
    }

    if (gameState.ended || elapsed > 60 || !ws.isOpen()) {
      println(status)
      println(
        s"Finished: game ended=${gameState.ended}, players won=${gameState.playersWon}, " +
          s"socket open=${ws.isOpen()}, $messagesReceived messages ($bytesReceived bytes), $actionsReceived actions"
      )
      ws.close()
      Event.quit(0)
    }
  }

  private def status: String = {
    val bosses = gameState.bosses.values.map(b => s"${b.name} life=${math.round(b.life)}").mkString(", ")
    val me = myId.flatMap(gameState.players.get).fold("-") { p =>
      val pos = p.currentPosition(System.currentTimeMillis())
      s"life=${math.round(p.life)} pos=(${math.round(pos.re)}, ${math.round(pos.im)})"
    }
    s"[t=${math.round(elapsed * 10) / 10.0}s] messages=$messagesReceived actions=$actionsReceived entities=${gameState.entities.size} " +
      s"boss: [$bosses] me: $me rtt=${lastRoundTrip}ms started=${gameState.started} ended=${gameState.ended}"
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
    case Pong(originalSendingTime, _, _) =>
      lastRoundTrip = System.currentTimeMillis() - originalSendingTime
    case HeartBeat =>
      ()
    case other =>
      println(s"<- unexpected $other")
  }
}
