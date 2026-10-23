package bffclient

import bffclient.ui.Colour as Paint
import gamelogic.abilities.boss.boss105.{Resonance, Reunion, Siphon, Skewer}
import gamelogic.abilities.boss.sirofvice.{CleansingNova, HolyWrath, SacredGround}
import gamelogic.buffs.boss.boss102.LivingDamageZone
import gamelogic.buffs.boss.boss104.TwinDebuff
import gamelogic.buffs.boss.boss105.{Mourning, TwinBond, VolatileCharge, Yearning}
import gamelogic.entities.{Body, Entity, EntityCastingInfo, LivingEntity}
import gamelogic.entities.boss.boss102.{BossHound, DamageZone}
import gamelogic.entities.boss.boss104.{
  BigGuy as Boss104BigGuy,
  BigGuyDeathMark,
  DeathMarkTriangle,
  DebuffCircle
}
import gamelogic.entities.boss.boss105.{ReformationPoint, Shade}
import gamelogic.entities.boss.dawnoftime.{Boss102, Boss105}
import gamelogic.entities.boss.firstraid.SirOfVice
import gamelogic.entities.boss.sirofvice.HolyWrathChain
import gamelogic.gamestate.GameState
import gamelogic.physics.Complex
import utils.misc.RGBColour

/** What the frontend draws specifically for each boss (its `bossspecificdrawers`): adds, zones,
  * auras, beams, ... The frontend chooses a drawer by boss name; here, the visuals are chosen by
  * the types of the entities and buffs (which are specific to their boss), so a boss without
  * visuals (Boss 101, Boss 110) simply has none.
  */
object BossVisuals {

  /** Drawn by `ground` or `adds` (so the renderer does not draw them as other bodies). */
  def draws(entity: Entity): Boolean = entity match {
    case _: BossHound | _: Boss104BigGuy | _: Shade => true
    case _: DamageZone | _: DebuffCircle | _: BigGuyDeathMark | _: DeathMarkTriangle |
        _: ReformationPoint | _: HolyWrathChain =>
      true
    case _ => false
  }

  private def paint(colour: RGBColour, alpha: Double = 1.0): Paint =
    Paint.rgb(colour.red, colour.green, colour.blue).withAlpha(alpha)

  private val addColour = Paint.rgb(50, 50, 50)

  // Boss 105
  private val bondColour      = Paint.rgb(80, 220, 240)
  private val exposedColour   = Paint.rgb(255, 150, 30)
  private val shadeColour     = Paint.rgb(90, 40, 140)
  private val resonanceColour = Paint.rgb(230, 30, 60)
  private val beamColour      = Paint.rgb(255, 60, 60)
  private val chargeColour    = Paint.rgb(255, 110, 20)
  private val siphonColour    = Paint.rgb(120, 220, 90)

  // Sir of Vice
  private val auraColour  = Paint.rgb(255, 200, 60)
  private val novaColour  = Paint(1, 1, 1)
  private val wrathColour = Paint.rgb(255, 240, 150)

  private def casting(p: Painter, casterId: Entity.Id): Option[EntityCastingInfo] =
    p.gameState.castingEntityInfo.get(casterId).filter(_.isCasting(p.now, 0L))

  private def progress(info: EntityCastingInfo, now: Long): Double =
    math.min(
      1.0,
      math.max(0.0, 1.0 - info.remainingCastingTime(now, 0L).toDouble / info.castingTime)
    )

  /** Zones, auras and the like, under the bodies. */
  def ground(p: Painter): Unit = {
    import p.*
    val time = now.toDouble

    // Boss 102: the damage zones, and the zone around the players who carry one
    for (zone <- gameState.allTEntities[DamageZone].values) {
      circle(zone.pos, DamageZone.radius, Some(Paint(0, 1, 0, 0.5)))
      for (sprite <- Sprites.damageZone) {
        val (x, y) = toScreen(zone.pos)
        Graphics.setColor(0.5, 0.5, 0, 0.3) // olive
        sprite.draw(x, y, 0, DamageZone.radius * 4, frame = ((now / 100) % sprite.frameCount).toInt)
      }
    }
    for {
      (id, player) <- gameState.players
      if gameState.allBuffsOfEntity(id).exists(_.isInstanceOf[LivingDamageZone])
    } Sprites.livingDamageZone match {
      case Some(sprite) =>
        val (x, y) = toScreen(position(player))
        Graphics.setColor(1, 1, 1, 0.3)
        sprite.draw(
          x,
          y,
          0,
          2 * LivingDamageZone.range * 192 / 120,
          frame = ((now / 50) % sprite.frameCount).toInt
        )
      case None =>
        circle(
          position(player),
          LivingDamageZone.range,
          Some(Paint(0.8, 0, 0, 0.2)),
          Some((2, Paint(0.8, 0, 0, 0.6)))
        )
    }

    // Boss 104: the circles of the twin debuffs, the debuffs on the players, the marks left by the
    // big guys and the triangle of the finisher (red while inside is deadly, green once it is the
    // only safe place)
    for (debuffCircle <- gameState.allTEntities[DebuffCircle].values)
      circle(debuffCircle.pos, debuffCircle.shape.radius, Some(paint(debuffCircle.colour, 0.5)))
    for {
      buff   <- gameState.allTBuffs[TwinDebuff]
      player <- gameState.players.get(buff.bearerId)
    }
      circle(position(player), player.shape.radius * 1.5, Some(paint(buff.colour, 0.5)))
    for (mark <- gameState.allTEntities[BigGuyDeathMark].values)
      circle(mark.pos, mark.shape.radius, Some(Paint(0, 0, 0, 0.5)))
    for (triangle <- gameState.allTEntities[DeathMarkTriangle].values) {
      val colour = if (triangle.insideIsDeadly) RGBColour.red else RGBColour.green
      polygon(triangle.vertices, Some(paint(colour, 0.15)), Some((3, paint(colour))))
    }

    // Boss 105
    for (case boss: Boss105 <- gameState.bosses.values) {
      val bossPosition = position(boss)

      // The twins' auras (half the resonance range) touch exactly when they resonate
      for (shade <- gameState.allTEntities[Shade].values) {
        val shadePosition = position(shade)
        val resonating    = bossPosition.distanceTo(shadePosition) < Resonance.range
        val colour        = if (resonating) resonanceColour else shadeColour
        val pulse         = if (resonating) 0.12 + 0.08 * math.sin(time / 80.0) else 0.0
        for (centre <- Seq(bossPosition, shadePosition))
          circle(
            centre,
            Resonance.range / 2,
            Some(colour.withAlpha(0.05 + pulse)),
            Some((2, colour.withAlpha(if (resonating) 0.9 else 0.35)))
          )
        line(
          bossPosition,
          shadePosition,
          if (resonating) 5 else 2,
          colour.withAlpha(if (resonating) 0.9 else 0.3)
        )
      }

      // Ring: cyan while bonded to its Shade, orange and pulsing when exposed
      val ringRadius = boss.shape.radius + 6
      if (gameState.hasBuffOfType[Mourning](boss.id))
        circle(
          bossPosition,
          ringRadius + 3 * math.sin(time / 120.0),
          Some(exposedColour.withAlpha(0.2)),
          Some((5, exposedColour))
        )
      else if (gameState.hasBuffOfType[TwinBond](boss.id))
        circle(bossPosition, ringRadius, None, Some((4, bondColour.withAlpha(0.9))))

      // The Skewer's beam, from the boss through its target up to the wall, while it is cast
      for {
        info <- casting(p, boss.id)
        case skewer: Skewer <- Some(info.ability)
        target <- gameState.players.get(skewer.targetId)
        direction = (position(target) - bossPosition).safeNormalized
        if direction.modulus2 > 0
      } {
        def toWall(from: Double, speed: Double) =
          if (speed > 1e-9) (Boss105.size - from) / speed
          else if (speed < -1e-9) (-Boss105.size - from) / speed
          else Double.MaxValue
        val length = math.max(
          0.0,
          math.min(toWall(bossPosition.re, direction.re), toWall(bossPosition.im, direction.im))
        )
        val side = direction.orthogonal * Skewer.halfWidth
        val end  = bossPosition + direction * length
        polygon(
          Seq(bossPosition - side, end - side, end + side, bossPosition + side),
          Some(beamColour.withAlpha(0.12 + 0.35 * progress(info, now))),
          Some((2, beamColour.withAlpha(0.8)))
        )
      }

      // The Reunion: while it is cast, and while the Shade is on its way
      val reunionAlpha = casting(p, boss.id)
        .filter(_.ability.isInstanceOf[Reunion])
        .map(info => 0.2 + 0.5 * progress(info, now))
        .orElse(
          Option.when(
            gameState.allTEntities[Shade].keys.exists(gameState.hasBuffOfType[Yearning](_))
          )(0.7)
        )
      for {
        alpha <- reunionAlpha
        shade <- gameState.allTEntities[Shade].values.headOption
      }
        line(bossPosition, position(shade), 6, shadeColour.withAlpha(alpha))
    }

    // Boss 105: the explosion radius of the charges, filling up until they explode
    for {
      charge <- gameState.allTBuffs[VolatileCharge]
      bearer <- gameState.players.get(charge.bearerId)
    } {
      val elapsed = math.min(
        1.0,
        math.max(0.0, (gameState.time - charge.appearanceTime).toDouble / VolatileCharge.duration)
      )
      circle(
        position(bearer),
        VolatileCharge.radius,
        Some(chargeColour.withAlpha(0.06)),
        Some((2, chargeColour.withAlpha(0.8)))
      )
      circle(
        position(bearer),
        math.max(1.0, VolatileCharge.radius * elapsed),
        Some(chargeColour.withAlpha(0.3))
      )
    }

    // Boss 105: where the Shade comes back, more and more solid until it is back
    for (point <- gameState.allTEntities[ReformationPoint].values) {
      val elapsed = gameState
        .allTBuffs[Mourning]
        .headOption
        .fold(0.0)(m =>
          math.min(1.0, (gameState.time - m.appearanceTime).toDouble / Mourning.duration)
        )
      circle(
        point.pos,
        ReformationPoint.radius,
        Some(shadeColour.withAlpha(0.1 + 0.3 * elapsed)),
        Some((2, shadeColour.withAlpha(0.8)))
      )
      polygon(
        Shade.shape.vertices.map(point.pos + _),
        Some(shadeColour.withAlpha(0.15 + 0.4 * elapsed)),
        Some((2, shadeColour))
      )
    }

    // Sir of Vice: its Sacred Ground aura, the Cleansing Nova growing until it reaches the walls, the
    // zone around the target of the Holy Wrath, and then (briefly) the path of the wrath
    for (case boss: SirOfVice <- gameState.bosses.values) {
      val centre = position(boss)
      circle(
        centre,
        SacredGround.radius,
        Some(auraColour.withAlpha(0.08)),
        Some((2, auraColour.withAlpha(0.5)))
      )
      for (info <- casting(p, boss.id)) info.ability match {
        case _: CleansingNova =>
          circle(
            centre,
            math.max(1.0, progress(info, now) * SirOfVice.roomRadius),
            Some(novaColour.withAlpha(0.1)),
            Some((3, novaColour.withAlpha(0.6)))
          )
        case wrath: HolyWrath =>
          for (target <- gameState.players.get(wrath.targetId))
            circle(
              position(target),
              HolyWrath.jumpRange,
              Some(wrathColour.withAlpha(0.15)),
              Some((2, wrathColour.withAlpha(0.8)))
            )
        case _ => ()
      }
    }
    for (
      chain <- gameState.allTEntities[HolyWrathChain].values
      if gameState.time - chain.time < HolyWrathChain.displayDuration
    ) {
      val alpha = 1.0 - (gameState.time - chain.time).toDouble / HolyWrathChain.displayDuration
      for ((from, to) <- chain.positions.zip(chain.positions.drop(1)))
        line(from, to, 4, wrathColour.withAlpha(alpha))
    }
  }

  /** How to tint the frame of the player in the HUD, like the frontend's `BossGUIPlugin`s: Boss
    * 104's twin debuffs must be dispelled from inside the circle of their colour, and the bearer of
    * Boss 105's volatile charge must be dispelled or go away from everybody (more and more
    * urgently). The colour of the contour, and of the background with its opacity.
    */
  def playerFrameTint(gameState: GameState, playerId: Entity.Id): Option[(Paint, Double)] =
    gameState.allBuffsOfEntity(playerId).collectFirst {
      case debuff: TwinDebuff => (paint(debuff.colour), 0.45)
      case charge: VolatileCharge =>
        val elapsed = math.min(
          1.0,
          math.max(0.0, (gameState.time - charge.appearanceTime).toDouble / VolatileCharge.duration)
        )
        (chargeColour, 0.25 + 0.5 * elapsed)
    }

  /** A boss-specific line of the HUD, like the frontend's `bossspecificcomponents`. */
  def hudText(gameState: GameState): Option[String] =
    Option.when(gameState.bosses.values.exists(_.name == Boss102.name))(
      s"Hounds: ${gameState.allTEntities[BossHound].size}"
    )

  /** The adds of the bosses. */
  def adds(p: Painter): Unit = {
    import p.*

    def withBars(add: LivingEntity & Body): Unit = { lifeBar(add); castingBar(add); buffIcons(add) }

    // Boss 102's hounds and Boss 104's big guys
    for (hound <- gameState.allTEntities[BossHound].values) {
      shape(hound, Some(addColour.withAlpha(0.3)), Some((2, addColour)))
      withBars(hound)
    }
    for (bigGuy <- gameState.allTEntities[Boss104BigGuy].values) {
      shape(bigGuy, Some(addColour.withAlpha(0.3)), Some((2, addColour)))
      withBars(bigGuy)
    }

    // Boss 105's Shade (outlined in white when it yearns for the boss), and the drain of its Siphon
    for (shade <- gameState.allTEntities[Shade].values) {
      val yearning = gameState.hasBuffOfType[Yearning](shade.id)
      for {
        info <- casting(p, shade.id)
        case siphon: Siphon <- Some(info.ability)
        target <- gameState.players.get(siphon.targetId)
      } line(
        position(shade),
        position(target),
        3,
        siphonColour.withAlpha(0.2 + 0.6 * progress(info, now))
      )
      shape(
        shade,
        Some(shadeColour.withAlpha(0.55)),
        Some((if (yearning) 4 else 2, if (yearning) Paint(1, 1, 1) else Paint(0, 0, 0)))
      )
      withBars(shade)
    }
  }
}
