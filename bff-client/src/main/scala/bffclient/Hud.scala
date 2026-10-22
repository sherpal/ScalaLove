package bffclient

import bffclient.ui.{Colour as Paint, Rect}
import gamelogic.abilities.{Ability, WithTargetAbility}
import gamelogic.entities.Entity
import utils.misc.RGBColour

/** The frontend's in-game UI components: the frames of the team on the left (`PlayerFrameContainer`),
  * the boss's frame on top (`BossFrame`), the cooldowns of the boss's abilities on the top right
  * (`BossCooldownsContainer`) and the threat meter on the bottom right (`BossThreadMeter`).
  *
  * Like in the frontend, clicking the frame of a player or of the boss targets it.
  */
final class Hud(client: GameClient) {

  /** The frames drawn in the last frame, to target by clicking them. */
  private var clickable = Vector.empty[(Rect, Entity.Id)]

  /** The entity whose frame is at this position on screen, if any. */
  def targetAt(x: Double, y: Double): Option[Entity.Id] =
    clickable.collectFirst { case (rect, id) if rect.contains(x, y) => id }

  def draw(p: Painter, width: Double, height: Double): Unit = {
    clickable = Vector.empty
    teamFrames(p)
    bossFrame(p, width)
    bossCooldowns(p, width)
    threatMeter(p, width, height)
    for (text <- BossVisuals.hudText(p.gameState)) {
      Graphics.setColor(0, 0, 0)
      Graphics.printf(text, width - 210, height / 2, 200, "right")
    }
  }

  private def paint(colour: RGBColour): Paint = Paint.rgb(colour.red, colour.green, colour.blue)

  private def fill(rect: Rect, colour: Paint): Unit = {
    Graphics.setColor(colour.r, colour.g, colour.b, colour.a)
    Graphics.rectangle("fill", rect.x, rect.y, rect.width, rect.height)
  }

  private def outline(rect: Rect, colour: Paint, lineWidth: Double = 1): Unit = {
    Graphics.setColor(colour.r, colour.g, colour.b, colour.a)
    Graphics.setLineWidth(lineWidth)
    Graphics.rectangle("line", rect.x + lineWidth / 2, rect.y + lineWidth / 2, rect.width - lineWidth, rect.height - lineWidth)
    Graphics.setLineWidth(1)
  }

  /** Text vertically centred in `rect`. */
  private def text(value: String, rect: Rect, colour: Paint, align: String = "left"): Unit = {
    Graphics.setColor(colour.r, colour.g, colour.b, colour.a)
    Graphics.printf(value, rect.x + 3, rect.y + (rect.height - 14) / 2, rect.width - 6, align)
  }

  private val black = Paint(0, 0, 0)
  private val white = Paint(1, 1, 1)

  private def textOn(colour: RGBColour): Paint = if (colour.isBright) black else white

  /** Green, then orange, then red, like the frontend's `StatusBar.lifeStatusColor`. */
  private def lifeColour(ratio: Double): Paint =
    if (ratio > 0.5) Paint(0, 0.8, 0) else if (ratio > 0.2) Paint(1, 0.65, 0) else Paint(0.9, 0, 0)

  private def ratio(value: Double, max: Double): Double = if (max <= 0) 0 else math.max(0.0, math.min(1.0, value / max))

  /** Players, alive or dead, sorted by name: class, name, life, resource and buffs. The frames of the
    * players out of healing range are faded.
    */
  private def teamFrames(p: Painter): Unit = {
    val gameState = p.gameState
    val me        = client.myId.flatMap(gameState.players.get)
    val players   = (gameState.players ++ gameState.deadPlayers).toVector.sortBy(_._2.name)
    for (((id, player), i) <- players.zipWithIndex) {
      val frame = Rect(0, 150 + i * 57, 150, 55)
      val alive = gameState.players.get(id)
      val alpha = (for {
        alivePlayer <- alive
        m           <- me if m.id != id
      } yield {
        val distance2 = (alivePlayer.currentPosition(p.now) - m.currentPosition(p.now)).modulus2
        if (distance2 > WithTargetAbility.healRangeSquared) 0.5 else 1.0
      }).getOrElse(1.0)

      fill(frame, Paint.rgb(204, 255, 255).withAlpha(alpha))
      for ((colour, opacity) <- BossVisuals.playerFrameTint(gameState, id)) {
        fill(frame, colour.withAlpha(opacity * alpha))
        outline(frame.inset(4), colour, 4)
      }
      outline(frame, black)

      val (r, g, b) = player.rgb
      Sprites.playerClasses.get(player.cls.value) match {
        case Some(sprite) =>
          Graphics.setColor(r / 255.0, g / 255.0, b / 255.0, alpha)
          sprite.draw(frame.x + 10, frame.y + 10, 0, 20)
        case None =>
          Graphics.setColor(r / 255.0, g / 255.0, b / 255.0, alpha)
          Graphics.circle("fill", frame.x + 10, frame.y + 10, 8)
      }

      val lifeBar = Rect(frame.x + 20, frame.y, frame.width - 20, 20)
      val life    = alive.fold(0.0)(_.life)
      val lifeRatio = ratio(life, player.maxLife)
      fill(Rect(lifeBar.x, lifeBar.y, lifeBar.width * lifeRatio, lifeBar.height), lifeColour(lifeRatio).withAlpha(alpha))
      text(player.name, lifeBar, black)
      text(alive.fold("Dead")(a => s"${math.round(a.life)}/${math.round(a.maxLife)}"), lifeBar, black, "right")

      val resourceBar = Rect(frame.x + 20, frame.y + 20, frame.width - 20, 15)
      for (a <- alive) {
        val resource = a.resourceAmount
        fill(Rect(resourceBar.x, resourceBar.y, resourceBar.width * ratio(resource.amount, a.maxResourceAmount), resourceBar.height),
          paint(a.resourceType.colour).withAlpha(0.6 * alpha)) // light enough for black text, like the frontend's
        text(s"${math.round(resource.amount)}/${math.round(a.maxResourceAmount)}", resourceBar, black, "right")
      }

      for (((buff, icon), j) <- p.iconBuffs(id).zipWithIndex.take(7))
        p.buffIcon(buff, icon, frame.x + 1 + j * 20, frame.y + 35, 19)

      if (client.currentTarget.contains(id)) outline(frame, Paint(1, 0, 0), 3)
      clickable :+= (frame, id)
    }
  }

  /** Life and name of the boss, and what it casts. */
  private def bossFrame(p: Painter, width: Double): Unit =
    for (boss <- p.gameState.bosses.values.headOption) {
      val lifeBar = Rect(width / 2 - 125, 0, 250, 20)
      fill(lifeBar, Paint.rgb(156, 156, 156))
      fill(Rect(lifeBar.x, lifeBar.y, lifeBar.width * ratio(boss.life, boss.maxLife), lifeBar.height), Paint(0, 0.8, 0))
      outline(lifeBar, black)
      text(boss.name, lifeBar, black, "center")
      for (info <- p.gameState.castingEntityInfo.get(boss.id) if info.isCasting(p.now, 0L)) {
        val castBar = Rect(lifeBar.x, lifeBar.bottom, lifeBar.width, 12)
        val colour  = info.ability.abilityColour
        fill(castBar, Paint.rgb(128, 128, 128))
        fill(Rect(castBar.x, castBar.y, castBar.width * ratio((p.now - info.startedTime).toDouble, info.castingTime.toDouble),
          castBar.height), paint(colour))
        outline(castBar, black)
        Graphics.setColor(textOn(colour).r, textOn(colour).g, textOn(colour).b)
        Graphics.printf(boss.abilityNames.getOrElse(info.ability.abilityId, ""), castBar.x, castBar.y - 1, castBar.width, "center")
      }
      clickable :+= (Rect(lifeBar.x, lifeBar.y, lifeBar.width, 32), boss.id)
    }

  /** One bar per ability of the boss, in its colour, emptying until it is ready again. */
  private def bossCooldowns(p: Painter, width: Double): Unit =
    for (boss <- p.gameState.bosses.values.headOption) {
      for (((abilityId, name), i) <- boss.abilityNames.toVector.zipWithIndex) {
        val row       = Rect(width - 150, i * 16, 150, 15)
        val colour    = Ability.abilityColour(abilityId)
        val lastUse   = boss.relevantUsedAbilities.get(abilityId)
        val remaining = lastUse.fold(0L)(use => math.max(0L, use.cooldown - (p.now - use.time)))
        fill(row, white.withAlpha(0.6))
        fill(Rect(row.x, row.y, row.width * ratio(remaining.toDouble, lastUse.fold(1L)(_.cooldown).toDouble), row.height),
          paint(colour).withAlpha(0.8))
        outline(row, black.withAlpha(0.5))
        text(name, row, black)
        if (remaining > 0)
          text(if (remaining >= 1000) (remaining / 1000).toString else s".${remaining / 100}", row, black, "right")
      }
    }

  /** The players by the boss's threat towards them, the highest first. */
  private def threatMeter(p: Painter, width: Double, height: Double): Unit =
    for (boss <- p.gameState.bosses.values.headOption) {
      val threats = boss.damageThreats.toVector
        .flatMap((id, amount) => p.gameState.players.get(id).map(_ -> amount.value))
        .sortBy(-_._2)
      val maxThreat = threats.headOption.map(_._2).filter(_ > 0).getOrElse(1.0)
      val left      = width - 150
      val top       = height - 4 - 16 * threats.size - 18
      Graphics.setColor(0, 0, 0)
      Graphics.print("Threat Meter", left, top)
      for (((player, amount), i) <- threats.zipWithIndex) {
        val row    = Rect(left, top + 18 + i * 16, 150, 15)
        val colour = RGBColour.fromIntColour(player.colour)
        fill(row, white.withAlpha(0.6))
        fill(Rect(row.x, row.y, row.width * ratio(amount, maxThreat), row.height), paint(colour).withAlpha(0.7))
        outline(row, black.withAlpha(0.5))
        text(player.name, row, black)
      }
    }
}
