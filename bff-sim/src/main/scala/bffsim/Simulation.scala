package bffsim

import gamelogic.abilities.Ability
import gamelogic.abilities.hexagon.FlashHeal
import gamelogic.abilities.pentagon.CreatePentagonBullet
import gamelogic.abilities.square.HammerHit
import gamelogic.abilities.triangle.DirectHit
import gamelogic.abilities.WithTargetAbility
import gamelogic.entities.Entity
import gamelogic.entities.boss.{BossEntity, BossFactory}
import gamelogic.entities.classes.{Constants, Hexagon, Pentagon, PlayerClass, Square, Triangle}
import gamelogic.gamestate.*
import gamelogic.gamestate.gameactions.*
import gamelogic.gamestate.serveractions.*
import gamelogic.physics.Complex
import gamelogic.utils.IdGeneratorContainer
import models.bff.ingame.InGameWSProtocol
import models.bff.outofgame.PlayerClasses

/** A headless, deterministic replay of what the game server does during a fight.
  *
  * It mirrors `GameMaster` + `InGameBehaviour` from BattleForFlatland's game-server, with a
  * simulated clock (one tick every 8 ms) instead of the wall clock. The boss has no AI; scripted
  * players walk to the boss and use their main ability, like (very) simple versions of the AIs.
  */
object Simulation {

  final val TickMillis = 8L
  final val StartTime  = 1000000L

  final case class Result(gameState: GameState, ticks: Int, actionCount: Long)

  /** Runs a fight. `onMessage` receives the in-game websocket messages that the server and the
    * clients would send.
    */
  def run(
      bossName: String,
      playersPerClass: Int,
      maxTicks: Int,
      log: String => Unit,
      onMessage: InGameWSProtocol => Unit = _ => ()
  ): Result = {
    given ids: IdGeneratorContainer = IdGeneratorContainer.initialIdGeneratorContainer

    val factory = BossFactory.factoriesByBossName(bossName)

    // Setup, as in GameMaster.start()
    var pending = Vector.empty[GameAction]
    for {
      cls <- PlayerClasses.allChoices
      i   <- 0 until playersPerClass
    } {
      val playerId = ids.entityId()
      pending = pending ++ (AddPlayerByClass(
        ids.actionId(),
        StartTime - 2,
        playerId,
        factory.playersStartingPosition,
        cls,
        0xff0000,
        s"${cls.value}$i"
      ) +: cls.builder.startingActions(StartTime - 1, playerId))
    }
    pending = pending ++ factory.stagingBossActions(StartTime)

    // The game begins, as in GameMaster.run()
    val bossId = ids.entityId()
    pending = pending ++ (SpawnBoss(ids.actionId(), StartTime + 8, bossId, bossName) +:
      factory.initialBossActions(bossId, StartTime + 9))
    pending = pending :+ GameStart(ids.actionId(), StartTime + 10)

    val serverAction = ManageUsedAbilities() ++
      ManageStopCastingMovements() ++
      ManageTickerBuffs() ++
      ManageBuffsToBeRemoved() ++
      ManageDeadPlayers() ++
      ManageEndOfGame() ++
      ManagePentagonBullets() ++
      ManageDeadAIs()

    var gatherer: ActionGatherer = GreedyActionGatherer(GameState.empty)
    var tick                     = 0
    var actionCount              = 0L

    while (tick < maxTicks && !gatherer.currentGameState.ended) {
      val time = StartTime + 16 + tick * TickMillis

      // InGameBehaviour.loop
      val sortedActions = pending.sorted.map(_.changeIdWithGen())
      pending = Vector.empty
      val (nextGatherer, oldestTimeToRemove, idsToRemove) =
        gatherer.masterAddAndRemoveActions(sortedActions)
      val (finalGatherer, output) = serverAction(nextGatherer, () => time)
      gatherer = finalGatherer
      actionCount += sortedActions.length + output.createdActions.length

      // What GameMaster sends to the clients
      val finalOutput = ServerAction
        .ServerActionOutput(sortedActions, oldestTimeToRemove, idsToRemove)
        .merge(output)
      if (finalOutput.createdActions.nonEmpty || finalOutput.idsOfIdsToRemove.nonEmpty)
        onMessage(
          InGameWSProtocol.AddAndRemoveActions(
            finalOutput.createdActions,
            finalOutput.oldestTimeToRemove,
            finalOutput.idsOfIdsToRemove
          )
        )

      // The "AIs" run at ~60 Hz, and send their actions like a client would
      if (tick % 2 == 0) {
        val actions = playerActions(gatherer.currentGameState, time)
        if (actions.nonEmpty) onMessage(InGameWSProtocol.GameActionWrapper(actions))
        pending = pending ++ actions
      }

      if (tick % 1000 == 0) log(summary(gatherer.currentGameState, tick, actionCount))
      tick += 1
    }

    log(summary(gatherer.currentGameState, tick, actionCount))
    Result(gatherer.currentGameState, tick, actionCount)
  }

  def summary(gs: GameState, tick: Int, actionCount: Long): String = {
    val bosses = gs.bosses.values.toList
      .sortBy(_.id.toString)
      .map(b => s"${b.name}=${round1(b.life)}")
      .mkString(",")
    val players = gs.players.values.toList
      .sortBy(_.id.toString)
      .map(p => s"${round1(p.life)}@${round1(p.pos.re)};${round1(p.pos.im)}")
      .mkString(" ")
    val buffs = gs.tickerBuffs.values.map(_.size).sum + gs.passiveBuffs.values.map(_.size).sum
    s"tick=$tick time=${gs.time} actions=$actionCount entities=${gs.entities.size} buffs=$buffs " +
      s"bosses=[$bosses] players=[$players] ended=${gs.ended}"
  }

  private def round1(d: Double): String = (math.round(d * 10) / 10.0).toString

  /** Each player walks to the boss and uses its main ability when possible. */
  private def playerActions(gs: GameState, time: Long): Vector[GameAction] =
    gs.bosses.values.headOption match {
      case None => Vector.empty
      case Some(boss) =>
        gs.players.values.toVector.sortBy(_.id.toString).flatMap { me =>
          val myPos     = me.currentPosition(time)
          val bossPos   = boss.currentPosition(time)
          val toBoss    = bossPos - myPos
          val casting   = gs.entityIsCasting(me.id)
          val wantRange = me match {
            case _: Triangle | _: Square => WithTargetAbility.meleeRange * 0.8
            case _ => 300.0
          }

          val movement: Option[GameAction] =
            if (casting) None
            else if (toBoss.modulus > wantRange) {
              val direction = toBoss.arg
              Option.unless(
                me.moving && Complex
                  .rotation(me.direction)
                  .distanceTo(Complex.rotation(direction)) < 1e-3
              )(
                MovingBodyMoves(
                  GameAction.Id.zero,
                  time,
                  me.id,
                  myPos,
                  direction,
                  direction,
                  Constants.playerSpeed,
                  moving = true
                )
              )
            } else
              Option.when(me.moving)(
                MovingBodyMoves(
                  GameAction.Id.zero,
                  time,
                  me.id,
                  myPos,
                  me.direction,
                  toBoss.arg,
                  Constants.playerSpeed,
                  moving = false
                )
              )

          val ability: Option[Ability] = me match {
            case _: Triangle =>
              Some(DirectHit(Ability.UseId.dummy, time, me.id, boss.id, DirectHit.directHitDamage))
            case _: Square =>
              Some(HammerHit(Ability.UseId.dummy, time, me.id, boss.id))
            case _: Pentagon =>
              Some(
                CreatePentagonBullet(
                  Ability.UseId.dummy,
                  time,
                  me.id,
                  myPos,
                  CreatePentagonBullet.damage,
                  toBoss.arg,
                  0x00ff00
                )
              )
            case _: Hexagon =>
              gs.players.values
                .filter(p => p.life < p.maxLife)
                .toList
                .sortBy(_.life)
                .headOption
                .map(target => FlashHeal(Ability.UseId.dummy, time, me.id, target.id))
            case _ => None
          }

          val cast = ability
            .filter(_ => !casting && movement.isEmpty)
            .filter(a => me.canUseAbilityBoolean(a, time, gs) && a.canBeCastBoolean(gs, time))
            .map(a => EntityStartsCasting(GameAction.Id.zero, time, a.castingTime, a))

          movement.toVector ++ cast.toVector
        }
    }
}
