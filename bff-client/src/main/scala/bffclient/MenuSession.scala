package bffclient

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

import io.circe.parser.decode
import menus.data.*
import models.bff.outofgame.{MenuGameWithPlayers, PlayerClasses}
import models.bff.outofgame.gameconfig.*
import utils.misc.RGBColour

/** Credentials to connect to a game server, received from the menu server. */
final case class GameAccess(host: String, port: Int, userName: String, gameId: String, secret: String)

/** Plays the menus against the menu server like the frontend does, without a UI: logs in, creates a
  * game, chooses a class and gets ready, picks the boss, starts the game and waits for the game
  * server credentials (pushed on the lobby websocket).
  */
final class MenuSession(menuHost: String, menuPort: Int, userName: String, playerClass: PlayerClasses,
    bossName: String)(using ExecutionContext) {

  private val http        = new HttpClient(s"http://$menuHost:$menuPort")
  private val credentials = Promise[GameAccess]()

  /** What happened so far, shown by the menu screen. */
  val log = scala.collection.mutable.ArrayBuffer.empty[String]
  def info(message: String): Unit = { log += message; println(s"[menu] $message") }

  def run(): Future[GameAccess] = for {
    _      <- Future.successful(info(s"Logging in as $userName"))
    _      <- http.post("/api/users/login", User(userName))
    me     <- http.get[Option[User]]("/api/users/me")
    _       = info(s"Logged in: $me (cookie ${http.sessionCookie})")
    games  <- http.get[Vector[MenuGameWithPlayers]]("/api/bff/games")
    _       = info(s"${games.size} game(s) on the server")
    game   <- http.postApi[CreateGameFormData, MenuGameWithPlayers]("/api/bff/new-game",
                CreateGameFormData(s"ScalaLove ${System.currentTimeMillis()}"))
    _       = info(s"Created game '${game.game.gameName}' (${game.id})")
    _      <- openLobby(game.id)
    _      <- http.postApi[ChangePlayerInfoFormData, MenuGameWithPlayers]("/api/bff/change-player-info",
                ChangePlayerInfoFormData(game.id, readyPlayerInfo))
    _       = info(s"Playing a $playerClass, ready")
    config <- http.postApi[ChangeGameMetadataFormData, MenuGameWithPlayers]("/api/bff/change-game-config",
                ChangeGameMetadataFormData(game.id, GameConfiguration.GameConfigMetadata(bossName)))
    _       = info(s"Boss: ${config.game.gameConfiguration.bossName}, configuration valid: ${config.game.gameConfigurationIsValid}")
    started <- http.postApi[GameIdFormData, Boolean]("/api/bff/start-game", GameIdFormData(game.id))
    _       = info(s"Start game: $started, waiting for the game server...")
    access <- credentials.future
    _       = info(s"Game server ready on port ${access.port}")
  } yield access

  private def readyPlayerInfo = PlayerInfo(
    PlayerName.HumanPlayerName(userName),
    Some(playerClass),
    Some(RGBColour(220, 40, 40)),
    PlayerStatus.Ready,
    PlayerType.Human
  )

  /** The lobby websocket tells when data changed and pushes the credentials. It must stay open (also
    * during the game): the menu server kicks the players who are not connected.
    */
  private def openLobby(gameId: String): Future[Unit] = {
    val connection = WebSocketModule.connect(
      s"ws://$menuHost:$menuPort/ws/bff/game-anti-chamber?gameId=$gameId", 5, http.sessionCookie)
    if (connection.socket == null) Future.failed(HttpError(s"Lobby websocket: ${connection.error}"))
    else {
      info("Lobby websocket connected")
      js.timers.setInterval(1)(pollLobby(connection.socket))
      Future.unit
    }
  }

  private def pollLobby(ws: WebSocket): Unit = {
    var data = ws.poll()
    while (data != null) {
      decode[MenuGameComm](data) match {
        case Right(MenuGameComm.HereAreYourCredentials(id, secret, port)) =>
          credentials.trySuccess(GameAccess(menuHost, port, userName, id, secret))
        case Right(MenuGameComm.GameStarted()) => info("<- GameStarted")
        case Right(MenuGameComm.DataUpdated()) => ()
        case Left(error)                       => info(s"Unknown lobby message $data: $error")
      }
      data = ws.poll()
    }
  }
}
