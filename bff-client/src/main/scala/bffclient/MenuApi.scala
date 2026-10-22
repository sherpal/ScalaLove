package bffclient

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

import io.circe.parser.decode
import menus.data.*
import models.bff.outofgame.MenuGameWithPlayers
import models.bff.outofgame.gameconfig.{GameConfiguration, PlayerInfo}

/** The endpoints of Battle for Flatland's menu server, like the frontend's `services.menugames`. */
final class MenuApi(val host: String, val port: Int)(using ExecutionContext) {
  private val http = new HttpClient(s"http://$host:$port")

  def sessionCookie: String = http.sessionCookie

  /** Logs in (the menu server only asks for a name) and returns the logged in user. */
  def login(name: String): Future[User] = for {
    _  <- http.post("/api/users/login", User(name))
    me <- me
    user <- me.fold(Future.failed(HttpError(s"Login as $name failed")))(Future.successful)
  } yield user

  def me: Future[Option[User]] = http.get[Option[User]]("/api/users/me")

  def games: Future[Vector[MenuGameWithPlayers]] = http.get[Vector[MenuGameWithPlayers]]("/api/bff/games")

  def game(gameId: String): Future[Option[MenuGameWithPlayers]] = games.map(_.find(_.id == gameId))

  def createGame(name: String): Future[MenuGameWithPlayers] =
    http.postApi[CreateGameFormData, MenuGameWithPlayers]("/api/bff/new-game", CreateGameFormData(name))

  def joinGame(gameId: String): Future[Vector[MenuGameWithPlayers]] =
    http.postApi[JoinGameFormData, Vector[MenuGameWithPlayers]]("/api/bff/join-game", JoinGameFormData(gameId, None))

  def leaveGame(gameId: String): Future[MenuGameWithPlayers] =
    http.postApi[GameIdFormData, MenuGameWithPlayers]("/api/bff/leave-game", GameIdFormData(gameId))

  def kickPlayer(gameId: String, playerName: String): Future[MenuGameWithPlayers] =
    http.postApi[KickPlayerFormData, MenuGameWithPlayers]("/api/bff/kick-player",
      KickPlayerFormData(gameId, playerName))

  def changePlayerInfo(gameId: String, info: PlayerInfo): Future[MenuGameWithPlayers] =
    http.postApi[ChangePlayerInfoFormData, MenuGameWithPlayers]("/api/bff/change-player-info",
      ChangePlayerInfoFormData(gameId, info))

  def changeGameConfig(gameId: String, metadata: GameConfiguration.GameConfigMetadata): Future[MenuGameWithPlayers] =
    http.postApi[ChangeGameMetadataFormData, MenuGameWithPlayers]("/api/bff/change-game-config",
      ChangeGameMetadataFormData(gameId, metadata))

  def addAI(gameId: String): Future[Boolean] =
    http.postApi[GameIdFormData, Boolean]("/api/bff/add-ai-to-game", GameIdFormData(gameId))

  def startGame(gameId: String): Future[Boolean] =
    http.postApi[GameIdFormData, Boolean]("/api/bff/start-game", GameIdFormData(gameId))

  /** The lobby websocket: tells when data changed, and pushes the game server credentials. With a
    * game id, it must stay open (also during the game): the menu server kicks the players who are
    * not connected.
    */
  def lobby(gameId: Option[String])(onMessage: MenuGameComm => Unit): Future[Lobby] = {
    val query      = gameId.fold("")(id => s"?gameId=$id")
    val connection = WebSocketModule.connect(s"ws://$host:$port/ws/bff/game-anti-chamber$query", 5, sessionCookie)
    if (connection.socket == null) Future.failed(HttpError(s"Lobby websocket: ${connection.error}"))
    else Future.successful(new Lobby(connection.socket, onMessage))
  }
}

/** An open lobby websocket, polled every frame. */
final class Lobby(ws: WebSocket, onMessage: MenuGameComm => Unit) {
  private val poller = js.timers.setInterval(1)(poll())
  private var closed = false

  def isOpen: Boolean = !closed && ws.isOpen()

  def close(): Unit = if (!closed) {
    closed = true
    js.timers.clearInterval(poller)
    ws.close()
  }

  private def poll(): Unit = {
    var data = ws.poll()
    while (data != null && !closed) {
      decode[MenuGameComm](data) match {
        case Right(message) => onMessage(message)
        case Left(error)    => println(s"[lobby] Unknown message $data: $error")
      }
      data = ws.poll()
    }
  }
}
