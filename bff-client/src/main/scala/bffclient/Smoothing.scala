package bffclient

import gamelogic.entities.{Entity, Moving, WithPosition}
import gamelogic.gamestate.GameState
import gamelogic.physics.Complex

/** Smooths the positions shown for the entities that the server moves (other players, bosses,
  * adds, projectiles), without delaying them.
  *
  * Their positions are extrapolated from the last movement received, so they jump when a new
  * movement does not match the extrapolation (a turn or a stop, learnt one round trip late). Such a
  * jump becomes an offset between the shown position and the extrapolated one, which then decays
  * exponentially (halved in `timeConstant * ln 2`). Steady movements have no offset, so they are not
  * delayed. Jumps larger than `snapDistance` (teleports) are shown as they are.
  *
  * Only for drawing: the game logic (targeting, casting, ...) uses the actual positions.
  */
final class Smoothing(timeConstant: Double = 0.1, snapDistance: Double = 100) {
  private final case class Smoothed(body: Moving, offset: Complex)
  private var smoothed = Map.empty[Entity.Id, Smoothed]

  /** Updates the offsets for this frame, `dt` seconds after the previous one. */
  def update(gameState: GameState, now: Long, dt: Double, except: Option[Entity.Id]): Unit = {
    val decay = math.exp(-dt / timeConstant)
    smoothed = gameState.entities.collect {
      case (id, body: Moving) if !except.contains(id) =>
        val offset = smoothed.get(id) match {
          case Some(Smoothed(previous, offset)) if previous ne body =>
            // Where we showed it (following the previous movement), relative to where it is now
            val jump = offset + previous.currentPosition(now) - body.currentPosition(now)
            if (jump.modulus > snapDistance) Complex.zero else jump * decay
          case Some(Smoothed(_, offset)) => offset * decay
          case None                      => Complex.zero
        }
        id -> Smoothed(body, if (offset.modulus < 0.01) Complex.zero else offset)
    }
  }

  /** Where to show the entity. */
  def position(entity: WithPosition, now: Long): Complex =
    entity.currentPosition(now) + smoothed.get(entity.id).fold(Complex.zero)(_.offset)
}
