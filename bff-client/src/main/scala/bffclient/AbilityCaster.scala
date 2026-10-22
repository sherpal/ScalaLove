package bffclient

import gamelogic.abilities.*
import gamelogic.abilities.hexagon.*
import gamelogic.abilities.pentagon.*
import gamelogic.abilities.square.*
import gamelogic.abilities.triangle.*
import gamelogic.entities.Entity
import gamelogic.entities.WithPosition.Angle
import gamelogic.entities.classes.PlayerClass
import gamelogic.entities.classes.hexagon.HexagonZone
import gamelogic.entities.classes.pentagon.PentagonZone
import gamelogic.gamestate.{GameAction, GameState}
import gamelogic.gamestate.gameactions.EntityStartsCasting
import gamelogic.physics.Complex
import utils.misc.RGBColour

/** What to do after the player asked to use an ability. */
enum CastRequest {
  case Send(action: EntityStartsCasting)
  case ChoosePosition(abilityId: Ability.AbilityId)
  case Error(message: String)
}

/** Builds the abilities of the players, like the frontend's `CastAbilitiesHandler`. */
final class AbilityCaster(myId: Entity.Id) {
  import CastRequest.*

  /** The ability on key `index + 1` (abilities in the same order as the frontend's action bar). */
  def castByIndex(index: Int, gameState: GameState, now: Long, target: Option[Entity.Id], mouse: Complex): CastRequest =
    gameState.players.get(myId) match {
      case None => Error("You are dead")
      case Some(player) =>
        val abilities = player.abilities.toArray
        if (index < abilities.length) cast(abilities(index), player, gameState, now, target, mouse)
        else Error(s"No ability on key ${index + 1}")
    }

  def cast(
      abilityId: Ability.AbilityId,
      player: PlayerClass,
      gameState: GameState,
      now: Long,
      target: Option[Entity.Id],
      mouse: Complex
  ): CastRequest = abilityId match {
    case Ability.hexagonFlashHealId =>
      withTarget(FlashHeal(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.hexagonHexagonHotId =>
      withTarget(HexagonHot(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.squareTauntId =>
      withTarget(Taunt(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.squareHammerHit =>
      withTarget(HammerHit(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.squareCleaveId =>
      val myPosition = player.currentPosition(now)
      val direction  = mouse - myPosition
      send(Cleave(Ability.UseId.zero, now, myId, myPosition + player.shape.radius * direction.normalized, direction.arg),
        gameState, now)
    case Ability.squareEnrageId =>
      send(Enrage(Ability.UseId.zero, now, myId), gameState, now)
    case Ability.triangleEnergyKick =>
      withTarget(EnergyKick(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.triangleDirectHit =>
      withTarget(DirectHit(Ability.UseId.zero, now, myId, _, DirectHit.directHitDamage), target, gameState, now)
    case Ability.triangleUpgradeDirectHit =>
      send(UpgradeDirectHit(Ability.UseId.zero, now, myId), gameState, now)
    case Ability.triangleStun =>
      withTarget(Stun(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.triangleCut =>
      withTarget(Cut(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.pentagonPentagonBullet =>
      val direction = Complex.rotation(player.rotation)
      send(CreatePentagonBullet(Ability.UseId.zero, now, myId, player.pos + player.shape.radius * direction,
        CreatePentagonBullet.damage, direction.arg, player.colour), gameState, now)
    case Ability.pentagonDispelId =>
      withTarget(PentaDispel(Ability.UseId.zero, now, myId, _), target, gameState, now)
    case Ability.createPentagonZoneId | Ability.hexagonHexagonZoneId =>
      ChoosePosition(abilityId)
    case other =>
      Error(s"Ability $other is not supported yet")
  }

  /** Zones are placed where the player clicks. */
  def castAtPosition(abilityId: Ability.AbilityId, position: Complex, gameState: GameState, now: Long): CastRequest =
    gameState.players.get(myId) match {
      case None => Error("You are dead")
      case Some(player) =>
        val colour = RGBColour.fromIntColour(player.colour).withAlpha(0.5)
        abilityId match {
          case Ability.createPentagonZoneId =>
            send(CreatePentagonZone(Ability.UseId.zero, now, myId, position, PentagonZone.damageOnTick, Angle.zero, colour),
              gameState, now)
          case Ability.hexagonHexagonZoneId =>
            send(CreateHexagonZone(Ability.UseId.zero, now, myId, position, HexagonZone.healOnTick, Angle.zero, colour),
              gameState, now)
          case other =>
            Error(s"Ability $other is not placed with the mouse")
        }
    }

  private def withTarget(ability: Entity.Id => Ability, target: Option[Entity.Id], gameState: GameState, now: Long) =
    target.fold(Error("No target"))(targetId => send(ability(targetId), gameState, now))

  private def send(ability: Ability, gameState: GameState, now: Long): CastRequest = {
    val action = EntityStartsCasting(GameAction.Id.zero, now, ability.castingTime, ability)
    action.isLegalDelay(gameState, 100).fold(Send(action))(Error(_))
  }
}
