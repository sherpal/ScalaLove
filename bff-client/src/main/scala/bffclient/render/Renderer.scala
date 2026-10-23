package bffclient.render

import bffclient.game.GameClient
import bffclient.hud.{Hud, HudStyle}
import bffclient.love.{Graphics, Mouse}
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
import bffclient.render.bosses.BossVisuals
import bffclient.ui.{Colour as Paint, Rect}

/** Draws the client-side game state with love.graphics, like the Indigo frontend's drawers: camera
  * centred on our player (y axis pointing up), obstacles, what is specific to the boss
  * (`BossVisuals`), zones, boss, players, and every other body as an outline.
  */
final class Renderer(client: GameClient) {

  /** The visuals of the boss being fought (kept once it died, for its adds that are left). */
  private var bossVisuals = Option.empty[BossVisuals]

  /** Team frames, boss frame, boss cooldowns and threat meter. */
  val hud = new Hud(client, () => bossVisuals)

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

  private val abilityNamesCache =
    scala.collection.mutable.Map.empty[String, Map[Ability.AbilityId, String]]

  private def abilityNames(player: PlayerClass): Map[Ability.AbilityId, String] =
    abilityNamesCache.getOrElseUpdate(
      player.cls.value,
      player.cls.builder.abilitiesDoc.map(doc => doc.abilityId -> doc.name).toMap
    )

  def draw(): Unit = {
    val gameState = client.gameState
    val now       = client.serverNow
    val screen    = Graphics.getDimensions()
    screenWidth = screen.width
    screenHeight = screen.height

    // Camera: our player, or the boss once we are dead
    val me = client.myId.flatMap(gameState.players.get)
    cameraPosition = me
      .map(_.currentPosition(now))
      .orElse(gameState.bosses.values.headOption.map(client.smoothing.position(_, now)))
      .getOrElse(cameraPosition)

    val painter =
      new Painter(gameState, now, cameraPosition, screen.width, screen.height, client.smoothing)
    import painter.*

    bossVisuals = BossVisuals.of(gameState).orElse(bossVisuals)

    Graphics.setBackgroundColor(200 / 255.0, 200 / 255.0, 200 / 255.0)

    // The aztec diamond, as in the Indigo frontend: twice its size, centred on the origin, half
    // transparent over the grey
    for background <- Sprites.aztecDiamond do {
      val (x, y) = toScreen(Complex.zero)
      Graphics.setColor(1, 1, 1, 0.5)
      background.draw(x, y, 0, background.frameWidth * 2)
    }

    // Origin, as in the Indigo frontend
    val (ox, oy) = toScreen(Complex.zero)
    Graphics.setColor(1, 1, 1)
    Graphics.circle("fill", ox, oy, 2)

    val entities = gameState.entities.values.toList.sortBy(_.id.toString)

    // What is specific to the boss, on the ground (zones, auras, beams, ...), under everything
    bossVisuals.foreach(_.ground(painter))

    // Obstacles
    for case obstacle: Obstacle <- entities do {
      Graphics.setColor(1, 1, 1)
      drawShape(obstacle, "fill")
      Graphics.setColor(0.6, 0.6, 0.6)
      drawShape(obstacle, "line")
    }

    // Zones and bullets, in the colour of their owner (like the frontend)
    def ownerColour(colour: Colour, alpha: Double): Unit =
      Graphics.setColor(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0, alpha)

    // Other bodies (zones, bullets, adds, ...)
    for case body: Body <- entities do body match {
      case _: Obstacle | _: BossEntity | _: PlayerClass => ()
      case drawn if bossVisuals.exists(_.draws(drawn)) => ()
      case zone: HexagonZone =>
        ownerColour(zone.colour, 0.3); drawShape(zone, "fill")
        ownerColour(zone.colour, 1); Graphics.setLineWidth(2); drawShape(zone, "line");
        Graphics.setLineWidth(1)
      case zone: PentagonZone =>
        ownerColour(zone.colour, 0.3); drawShape(zone, "fill")
        ownerColour(zone.colour, 1); Graphics.setLineWidth(2); drawShape(zone, "line");
        Graphics.setLineWidth(1)
      case bullet: PentagonBullet =>
        ownerColour(RGBColour.fromIntColour(bullet.colour), 1)
        drawShape(bullet, "fill")
      case other =>
        Graphics.setColor(0.2, 0.2, 0.2)
        drawShape(other, "line")
        other match {
          case living: LivingEntity =>
            lifeBar(living.asInstanceOf[LivingEntity & Body])
            buffIcons(living.asInstanceOf[LivingEntity & Body])
          case _ => ()
        }
    }

    // The adds of the bosses
    bossVisuals.foreach(_.adds(painter))

    // Bosses
    for boss <- gameState.bosses.values do {
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
      for
        sprite <- Sprites.casting
        info   <- gameState.castingEntityInfo.get(player.id) if info.isCasting(now, 0L)
      do {
        val colour = info.ability.abilityColour
        Graphics.setColor(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0)
        val frame = ((now / 50) % sprite.frameCount).toInt
        for side <- Seq(1, -1) do {
          val angle =
            rotation(player) + Angle.fromDouble(side * 2 * math.Pi / player.shape.vertices.length)
          val (x, y) = toScreen(position(player) + Complex.polar(player.shape.radius, angle))
          sprite.draw(x, y, 0, 16, frame = frame)
        }
      }

    // Players: the frontend's sprite of their class, tinted with their colour (or their shape)
    for player <- gameState.players.values do {
      val (r, g, b) = player.rgb
      Sprites.playerClasses.get(player.cls.value) match {
        case Some(sprite) =>
          val (x, y) = toScreen(position(player))
          Graphics.setColor(r / 255.0, g / 255.0, b / 255.0)
          sprite.draw(x, y, rotation(player).value, 2 * player.shape.radius)
          val (noseX, noseY) = toScreen(
            position(player) + Complex.polar(player.shape.radius, rotation(player))
          )
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
    for
      id <- client.currentTarget
      case target: Body <- gameState.entities.get(id)
    do {
      val (x, y) = toScreen(position(target))
      if client.lockInToTarget then Graphics.setColor(1, 0.3, 0) else Graphics.setColor(1, 0.85, 0)
      Graphics.setLineWidth(2)
      Graphics.circle("line", x, y, target.shape.radius + 6)
      Graphics.setLineWidth(1)
    }

    // Game markers (put with the marker controls)
    for (marker, info) <- gameState.markersInfo if !client.isCountdownSignal(info) do {
      val markerPosition = info match {
        case GameMarkerInfo.FixedGameMarker(_, gamePosition) => Some(gamePosition)
        case GameMarkerInfo.GameMarkerOnEntity(_, entityId) =>
          // Above the buff icons (of the players and bosses)
          gameState.entities.get(entityId).collect { case b: Body =>
            val aboveIcons = if iconBuffs(entityId).nonEmpty then 20 else 0
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
    for _ <- client.choosingPosition do {
      val (x, y) = toScreen(mouseWorld)
      Graphics.setColor(1, 1, 1, 0.4)
      Graphics.circle("fill", x, y, 45)
    }

    infoLines(gameState, screen.width)
    hud.draw(painter, screen.width, screen.height)
    actionBar(gameState, now, screen.width, screen.height)
  }

  /** Our abilities, in the [[HudStyle]]: icon (pushed down just after it is used, darkened from the
    * top during its cooldown, like the frontend's), key, name and remaining cooldown.
    */
  private def actionBar(gameState: GameState, now: Long, width: Int, height: Int): Unit =
    for
      id <- client.myId
      me <- gameState.players.get(id)
    do {
      import HudStyle.*
      val names     = abilityNames(me)
      val abilities = me.abilities.toArray
      val slot      = 176.0
      val left      = (width - slot * abilities.length) / 2
      val top       = height - 64.0
      for (abilityId, i) <- abilities.zipWithIndex do {
        val rect     = Rect(left + i * slot + 2, top, slot - 4, 44)
        val used     = me.relevantUsedAbilities.get(abilityId)
        val cooldown = used.map(u => u.time + u.cooldown - now).filter(_ > 0)
        panel(rect)
        // The colour of the ability, as a line at the bottom
        roundedFill(
          Rect(rect.x + 6, rect.bottom - 5, rect.width - 12, 2),
          1,
          softened(Ability.abilityColour(abilityId))
        )
        val textLeft = Sprites.abilityIcons.get(abilityId) match {
          case Some(icon) =>
            val iconTop = rect.y + 5 + (if used.exists(u => now - u.time < 100) then 3 else 0)
            Graphics.setColor(1, 1, 1)
            icon.draw(rect.x + 22, iconTop + 16, 0, 32, 32)
            for
              remaining <- cooldown
              u         <- used
            do {
              Graphics.setColor(0, 0, 0, 0.55)
              Graphics.rectangle(
                "fill",
                rect.x + 6,
                iconTop,
                32,
                32 * remaining / u.cooldown.toDouble
              )
            }
            // A thin dark border, so that the (often white) icons do not glare on the panels
            roundedLine(Rect(rect.x + 5, iconTop - 1, 34, 34), 3, Paint(0, 0, 0, 0.85), 2)
            rect.x + 42
          case None =>
            for
              remaining <- cooldown
              u         <- used
            do
              roundedFill(
                Rect(rect.x, rect.y, rect.width * remaining / u.cooldown.toDouble, rect.height),
                6,
                Paint(0, 0, 0, 0.45)
              )
            rect.x + 2
        }
        val keyLabel = client.controls.abilityKeys.lift(i).fold("-")(_.label)
        text(
          keyLabel,
          Rect(textLeft, rect.y + 4, rect.right - textLeft - 2, 16),
          colour = mutedColour
        )
        text(
          names.getOrElse(abilityId, s"#$abilityId"),
          Rect(textLeft, rect.y + 19, rect.right - textLeft - 2, 18)
        )
        cooldown.foreach(r =>
          text(
            f"${r / 1000.0}%.1f",
            Rect(textLeft, rect.y + 4, rect.right - textLeft - 2, 16),
            "right"
          )
        )
      }
    }

  /** Ping, controls, target and messages (the lives are in the HUD's frames). */
  private def infoLines(gameState: GameState, width: Int): Unit = {
    Graphics.setColor(0, 0, 0)
    Graphics.print(s"ping: ${client.roundTrip} ms   messages: ${client.messagesReceived}", 10, 72)
    val c = client.controls
    Graphics.print(
      s"Move: ${c.upKey.label} ${c.leftKey.label} ${c.downKey.label} ${c.rightKey.label}   " +
        s"Next target: ${c.nextTargetKey.label} (or click)   " +
        s"Face target: ${c.targetLockInToggleKey.label} (${
            if client.lockInToTarget then "on"
            else "off"
          })   " +
        s"Mouse over: ${if c.mouseOverEnabled then "on" else "off"}   Escape: menu",
      10,
      90
    )
    val target = client.currentTarget
      .flatMap(gameState.entities.get)
      .collect { case l: LivingEntity => l }
      .fold("Target: none") { t =>
        val name = t match {
          case named: WithName => named.name
          case other => other.getClass.getSimpleName
        }
        s"Target: $name (${math.round(t.life)} / ${math.round(t.maxLife)})"
      }
    Graphics.print(target, 10, 108)
    Graphics.setColor(0.3, 0.3, 0.3)
    Graphics.print(client.controlsInfo, 10, 126)
    Graphics.setColor(0, 0, 0)
    val message =
      if gameState.ended then if gameState.playersWon then "Victory!" else "Defeat..."
      else if !gameState.started && !client.inPreGame then "Waiting for the game to start..."
      else ""
    if message.nonEmpty then Graphics.printf(message, 0, 170, width, "center")
    client.errorMessage.foreach { error =>
      Graphics.setColor(0.8, 0, 0)
      Graphics.printf(error, 0, 190, width, "center")
    }
    if client.choosingPosition.isDefined then {
      Graphics.setColor(0, 0, 0)
      Graphics.printf("Click to place the zone (Escape to cancel)", 0, 210, width, "center")
    }
  }
}
