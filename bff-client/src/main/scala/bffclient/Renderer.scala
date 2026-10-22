package bffclient

import gamelogic.abilities.Ability
import gamelogic.entities.{Body, Entity, LivingEntity, WithName, WithPosition}
import gamelogic.entities.WithPosition.Angle
import gamelogic.gameextras.GameMarkerInfo
import gamelogic.entities.boss.BossEntity
import gamelogic.entities.classes.PlayerClass
import gamelogic.entities.classes.hexagon.HexagonZone
import gamelogic.entities.classes.pentagon.PentagonZone
import gamelogic.entities.movingstuff.PentagonBullet
import gamelogic.entities.staticstuff.Obstacle
import utils.misc.{Colour, RGBColour}
import gamelogic.gamestate.GameState
import gamelogic.physics.Complex
import gamelogic.physics.shape.{Circle, Polygon}

/** Draws the client-side game state with love.graphics, like the Indigo frontend's drawers:
  * camera centred on our player (y axis pointing up), obstacles, what is specific to the boss
  * (`BossVisuals`), zones, boss, players, and every other body as an outline.
  */
final class Renderer(client: GameClient) {

  private var cameraPosition = Complex.zero
  private var screenWidth    = 800.0
  private var screenHeight   = 600.0

  /** Screen (pixels) to world coordinates, with the camera of the last frame. */
  def toWorld(x: Double, y: Double): Complex =
    cameraPosition + Complex(x - screenWidth / 2, screenHeight / 2 - y)

  def mouseWorld: Complex = {
    val mouse = Mouse.getPosition()
    toWorld(mouse.x, mouse.y)
  }

  private val abilityNamesCache = scala.collection.mutable.Map.empty[String, Map[Ability.AbilityId, String]]

  private def abilityNames(player: PlayerClass): Map[Ability.AbilityId, String] =
    abilityNamesCache.getOrElseUpdate(player.cls.value,
      player.cls.builder.abilitiesDoc.map(doc => doc.abilityId -> doc.name).toMap)

  def draw(): Unit = {
    val gameState = client.gameState
    val now       = client.serverNow
    val screen    = Graphics.getDimensions()
    screenWidth = screen.width
    screenHeight = screen.height

    // Camera: our player, or the boss once we are dead
    val me = client.myId.flatMap(gameState.players.get)
    cameraPosition = me.map(_.currentPosition(now))
      .orElse(gameState.bosses.values.headOption.map(client.smoothing.position(_, now)))
      .getOrElse(cameraPosition)

    val painter = new Painter(gameState, now, cameraPosition, screen.width, screen.height, client.smoothing)
    import painter.*

    Graphics.setBackgroundColor(200 / 255.0, 200 / 255.0, 200 / 255.0)

    // Origin, as in the Indigo frontend
    val (ox, oy) = toScreen(Complex.zero)
    Graphics.setColor(1, 1, 1)
    Graphics.circle("fill", ox, oy, 2)

    val entities = gameState.entities.values.toList.sortBy(_.id.toString)

    // What is specific to the boss, on the ground (zones, auras, beams, ...), under everything
    BossVisuals.ground(painter)

    // Obstacles
    for (case obstacle: Obstacle <- entities) {
      Graphics.setColor(1, 1, 1)
      drawShape(obstacle, "fill")
      Graphics.setColor(0.6, 0.6, 0.6)
      drawShape(obstacle, "line")
    }

    // Zones and bullets, in the colour of their owner (like the frontend)
    def ownerColour(colour: Colour, alpha: Double): Unit =
      Graphics.setColor(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0, alpha)

    // Other bodies (zones, bullets, adds, ...)
    for (case body: Body <- entities) body match {
      case _: Obstacle | _: BossEntity | _: PlayerClass => ()
      case drawn if BossVisuals.draws(drawn)           => ()
      case zone: HexagonZone =>
        ownerColour(zone.colour, 0.3); drawShape(zone, "fill")
        ownerColour(zone.colour, 1); Graphics.setLineWidth(2); drawShape(zone, "line"); Graphics.setLineWidth(1)
      case zone: PentagonZone =>
        ownerColour(zone.colour, 0.3); drawShape(zone, "fill")
        ownerColour(zone.colour, 1); Graphics.setLineWidth(2); drawShape(zone, "line"); Graphics.setLineWidth(1)
      case bullet: PentagonBullet =>
        ownerColour(RGBColour.fromIntColour(bullet.colour), 1)
        drawShape(bullet, "fill")
      case other =>
        Graphics.setColor(0.2, 0.2, 0.2)
        drawShape(other, "line")
        other match {
          case living: LivingEntity => lifeBar(living.asInstanceOf[LivingEntity & Body])
          case _                    => ()
        }
    }

    // The adds of the bosses
    BossVisuals.adds(painter)

    // Bosses
    for (boss <- gameState.bosses.values) {
      Graphics.setColor(1, 1, 1)
      drawShape(boss, "fill")
      Graphics.setColor(0, 0, 0)
      drawShape(boss, "line")
      nose(boss, (0, 0, 0))
      lifeBar(boss)
      castingBar(boss)
      buffIcons(boss)
    }

    // Sparks in the hands of a casting player, in the colour of the ability (like the frontend)
    def castingSparks(player: PlayerClass): Unit =
      for {
        sprite <- Sprites.casting
        info   <- gameState.castingEntityInfo.get(player.id) if info.isCasting(now, 0L)
      } {
        val colour = info.ability.abilityColour
        Graphics.setColor(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0)
        val frame = ((now / 50) % sprite.frameCount).toInt
        for (side <- Seq(1, -1)) {
          val angle  = rotation(player) + Angle.fromDouble(side * 2 * math.Pi / player.shape.vertices.length)
          val (x, y) = toScreen(position(player) + Complex.polar(player.shape.radius, angle))
          sprite.draw(x, y, 0, 16, frame = frame)
        }
      }

    // Players: the frontend's sprite of their class, tinted with their colour (or their shape)
    for (player <- gameState.players.values) {
      val (r, g, b) = player.rgb
      Sprites.playerClasses.get(player.cls.value) match {
        case Some(sprite) =>
          val (x, y) = toScreen(position(player))
          Graphics.setColor(r / 255.0, g / 255.0, b / 255.0)
          sprite.draw(x, y, rotation(player).value, 2 * player.shape.radius)
          val (noseX, noseY) = toScreen(position(player) + Complex.polar(player.shape.radius, rotation(player)))
          Graphics.setColor(1, 1, 1)
          Graphics.circle("fill", noseX, noseY, 2)
        case None =>
          Graphics.setColor(r / 255.0, g / 255.0, b / 255.0)
          drawShape(player, "fill")
          Graphics.setColor(0, 0, 0)
          drawShape(player, "line")
          nose(player, (1, 1, 1))
      }
      castingSparks(player)
      lifeBar(player)
      castingBar(player)
      buffIcons(player)
    }

    // Effects of the abilities (numbers, cleaves, ...)
    client.effects.draw(
      id => gameState.entities.get(id).collect { case entity: WithPosition => position(entity) },
      toScreen
    )

    // Target
    for (id <- client.currentTarget; case target: Body <- gameState.entities.get(id)) {
      val (x, y) = toScreen(position(target))
      if (client.lockInToTarget) Graphics.setColor(1, 0.3, 0) else Graphics.setColor(1, 0.85, 0)
      Graphics.setLineWidth(2)
      Graphics.circle("line", x, y, target.shape.radius + 6)
      Graphics.setLineWidth(1)
    }

    // Game markers (put with the marker controls)
    for ((marker, info) <- gameState.markersInfo) {
      val markerPosition = info match {
        case GameMarkerInfo.FixedGameMarker(_, gamePosition) => Some(gamePosition)
        case GameMarkerInfo.GameMarkerOnEntity(_, entityId) =>
          // Above the buff icons (of the players and bosses)
          gameState.entities.get(entityId).collect { case b: Body =>
            val aboveIcons = if (iconBuffs(entityId).nonEmpty) 20 else 0
            position(b) + Complex.i * (b.shape.radius + 24 + aboveIcons)
          }
      }
      markerPosition.foreach { p =>
        val (x, y) = toScreen(p)
        Graphics.setColor(0.1, 0.1, 0.6)
        Graphics.circle("fill", x, y, 9)
        Graphics.setColor(1, 1, 1)
        Graphics.printf(marker.toString.take(1), x - 9, y - 7, 18, "center")
      }
    }

    // Zone placement preview
    for (_ <- client.choosingPosition) {
      val (x, y) = toScreen(mouseWorld)
      Graphics.setColor(1, 1, 1, 0.4)
      Graphics.circle("fill", x, y, 45)
    }

    hud(gameState, screen.width)
    actionBar(gameState, now, screen.width, screen.height)
  }

  private def actionBar(gameState: GameState, now: Long, width: Int, height: Int): Unit =
    for (id <- client.myId; me <- gameState.players.get(id)) {
      val names     = abilityNames(me)
      val abilities = me.abilities.toArray
      val slot      = 176.0
      val left      = (width - slot * abilities.length) / 2
      val top       = height - 64.0
      for ((abilityId, i) <- abilities.zipWithIndex) {
        val x        = left + i * slot
        val used     = me.relevantUsedAbilities.get(abilityId)
        val cooldown = used.map(u => u.time + u.cooldown - now).filter(_ > 0)
        Graphics.setColor(1, 1, 1, 0.85)
        Graphics.rectangle("fill", x + 2, top, slot - 4, 44)
        val colour = Ability.abilityColour(abilityId)
        Graphics.setColor(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0)
        Graphics.rectangle("fill", x + 2, top, slot - 4, 4)
        val textLeft = Sprites.abilityIcons.get(abilityId) match {
          case Some(icon) =>
            // Like the frontend: pushed down just after it is used, darkened from the top during its cooldown
            val iconTop = top + 6 + (if (used.exists(u => now - u.time < 100)) 3 else 0)
            Graphics.setColor(1, 1, 1)
            icon.draw(x + 24, iconTop + 16, 0, 32, 32)
            for (remaining <- cooldown; u <- used) {
              Graphics.setColor(0, 0, 0, 0.5)
              Graphics.rectangle("fill", x + 8, iconTop, 32, 32 * remaining / u.cooldown.toDouble)
            }
            x + 46
          case None =>
            for (remaining <- cooldown; u <- used) {
              Graphics.setColor(0.3, 0.3, 0.3, 0.6)
              Graphics.rectangle("fill", x + 2, top, (slot - 4) * remaining / u.cooldown.toDouble, 44)
            }
            x + 6
        }
        Graphics.setColor(0, 0, 0)
        Graphics.rectangle("line", x + 2, top, slot - 4, 44)
        val keyLabel = client.controls.abilityKeys.lift(i).fold("-")(_.label)
        Graphics.print(s"[$keyLabel]", textLeft, top + 8)
        Graphics.printf(names.getOrElse(abilityId, s"#$abilityId"), textLeft, top + 24, x + slot - 4 - textLeft, "left")
        cooldown.foreach(r => Graphics.print(s"${(r + 99) / 100 / 10.0}s", x + slot - 36, top + 8))
      }
      val resource = me.resourceAmount
      Graphics.setColor(0, 0, 0)
      Graphics.print(s"${resource.resourceType}: ${math.round(resource.amount)} / ${math.round(me.maxResourceAmount)}",
        left + 2, top - 18)
    }

  private def hud(gameState: GameState, width: Int): Unit = {
    Graphics.setColor(0, 0, 0)
    val boss = gameState.bosses.values.headOption
      .fold("No boss")(b => s"${b.name}: ${math.round(b.life)} / ${math.round(b.maxLife)}")
    val me = client.myId.flatMap(gameState.players.get)
      .fold("You are dead (or not yet spawned)")(p => s"You: ${math.round(p.life)} / ${math.round(p.maxLife)}")
    Graphics.print(boss, 10, 10)
    Graphics.print(me, 10, 28)
    Graphics.print(s"ping: ${client.roundTrip} ms   messages: ${client.messagesReceived}", 10, 46)
    val c = client.controls
    Graphics.print(
      s"Move: ${c.upKey.label} ${c.leftKey.label} ${c.downKey.label} ${c.rightKey.label}   " +
        s"Next target: ${c.nextTargetKey.label} (or click)   " +
        s"Face target: ${c.targetLockInToggleKey.label} (${if (client.lockInToTarget) "on" else "off"})   Escape: menu",
      10, 64)
    val target = client.currentTarget.flatMap(gameState.entities.get).collect { case l: LivingEntity => l }
      .fold("Target: none") { t =>
        val name = t match {
          case named: WithName => named.name
          case other           => other.getClass.getSimpleName
        }
        s"Target: $name (${math.round(t.life)} / ${math.round(t.maxLife)})"
      }
    Graphics.print(target, 10, 82)
    Graphics.setColor(0.3, 0.3, 0.3)
    Graphics.print(client.controlsInfo, 10, 100)
    Graphics.setColor(0, 0, 0)
    val message =
      if (gameState.ended) (if (gameState.playersWon) "Victory!" else "Defeat...")
      else if (!gameState.started) "Waiting for the game to start..."
      else ""
    if (message.nonEmpty) Graphics.printf(message, 0, 120, width, "center")
    client.errorMessage.foreach { error =>
      Graphics.setColor(0.8, 0, 0)
      Graphics.printf(error, 0, 140, width, "center")
    }
    if (client.choosingPosition.isDefined) {
      Graphics.setColor(0, 0, 0)
      Graphics.printf("Click to place the zone (Escape to cancel)", 0, 160, width, "center")
    }
  }
}
