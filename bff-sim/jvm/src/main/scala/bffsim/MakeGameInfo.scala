package bffsim

import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import menus.data.User
import models.bff.ingame.GameUserCredentials
import models.bff.outofgame.{MenuGame, MenuGameWithPlayers, PlayerClasses}
import models.bff.outofgame.gameconfig.*
import utils.misc.RGBColour

/** Builds the last argument of BattleForFlatland's game server (normally sent by the game server
  * launcher): the game info and the credentials of every player, as base64-encoded JSON.
  *
  * Usage: MakeGameInfo <gameId> <gameSecret> <bossName> <user>:<class>:<secret>...
  */
object MakeGameInfo {

  // Same shapes as BattleForFlatland's shared-backend `menus.data` classes, hence the same JSON
  final case class GameCredentials(gameId: String, gameSecret: String)
  final case class AllGameCredentials(
      gameCredentials: GameCredentials,
      allGameUserCredentials: Vector[GameUserCredentials]
  )
  final case class GameCredentialsWithGameInfo(
      allGameCredentials: AllGameCredentials,
      gameInfo: MenuGameWithPlayers
  )
  given Codec[GameCredentials]             = deriveCodec
  given Codec[AllGameCredentials]          = deriveCodec
  given Codec[GameCredentialsWithGameInfo] = deriveCodec

  def main(args: Array[String]): Unit = {
    val gameId +: gameSecret +: bossName +: players = args.toVector: @unchecked
    val parsed                                      = players.map { p =>
      val Array(name, cls, secret) = p.split(":"): @unchecked
      (
        name,
        PlayerClasses.playerClassByName(cls).getOrElse(sys.error(s"Unknown class $cls")),
        secret
      )
    }
    val playersInfo = parsed.map { (name, cls, _) =>
      name -> PlayerInfo(
        PlayerName.HumanPlayerName(name),
        Some(cls),
        Some(RGBColour(255, 0, 0)),
        PlayerStatus.Ready,
        PlayerType.Human
      )
    }.toMap
    val users = parsed.map((name, _, _) => User(name))
    val game  = MenuGame(
      gameId,
      "ScalaLove test game",
      None,
      users.head,
      LocalDateTime.of(2026, 10, 22, 12, 0),
      GameConfiguration(playersInfo, bossName)
    )
    val info = GameCredentialsWithGameInfo(
      AllGameCredentials(
        GameCredentials(gameId, gameSecret),
        parsed.map((name, _, secret) => GameUserCredentials(name, gameId, secret))
      ),
      MenuGameWithPlayers(game, users, started = true)
    )
    val json = Codec[GameCredentialsWithGameInfo].apply(info).noSpaces
    print(java.util.Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8)))
  }
}
