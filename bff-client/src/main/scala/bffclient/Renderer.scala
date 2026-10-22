package bffclient

import gamelogic.entities.{Body, Entity, LivingEntity}
import gamelogic.entities.boss.BossEntity
import gamelogic.entities.classes.PlayerClass
import gamelogic.entities.staticstuff.Obstacle
import gamelogic.gamestate.GameState
import gamelogic.physics.Complex
import gamelogic.physics.shape.{Circle, Polygon}

/** Draws the client-side game state with love.graphics, like the Indigo frontend's drawers:
  * camera centred on our player (y axis pointing up), obstacles, boss, players, and every other
  * body as an outline.
  */
final class Renderer(client: GameClient) {

  private var cameraPosition = Complex.zero

  def draw(): Unit = {
    val gameState = client.gameState
    val now       = client.serverNow
    val screen    = Graphics.getDimensions()

    // Camera: our player, or the boss once we are dead
    val me = client.myId.flatMap(gameState.players.get)
    cameraPosition = me.map(_.currentPosition(now))
      .orElse(gameState.bosses.values.headOption.map(_.currentPosition(now)))
      .getOrElse(cameraPosition)

    def toScreen(z: Complex): (Double, Double) = {
      val d = z - cameraPosition
      (screen.width / 2.0 + d.re, screen.height / 2.0 - d.im)
    }

    def polygonCoords(points: Iterable[Complex]): Seq[Double] =
      points.toSeq.flatMap { p => val (x, y) = toScreen(p); Seq(x, y) }

    def drawShape(body: Body, mode: String): Unit = {
      val pos = body.currentPosition(now)
      body.shape match {
        case circle: Circle =>
          val (x, y) = toScreen(pos)
          Graphics.circle(mode, x, y, circle.radius)
        case polygon: Polygon if mode == "fill" =>
          // Fill triangle by triangle, as obstacles can be concave
          for (t <- polygon.triangulation) {
            val corners = Seq(Complex(t.x0, t.y0), Complex(t.x1, t.y1), Complex(t.x2, t.y2))
              .map(_.rotate(body.rotation) + pos)
            Graphics.polygon("fill", polygonCoords(corners)*)
          }
        case polygon: Polygon =>
          Graphics.polygon("line", polygonCoords(polygon.translateAndRotationVertices(pos, body.rotation))*)
        case _ =>
          ()
      }
    }

    def nose(body: Body, colour: (Double, Double, Double)): Unit = {
      val pos     = body.currentPosition(now)
      val (x0, y0) = toScreen(pos)
      val (x1, y1) = toScreen(pos + Complex.polar(body.shape.radius, body.rotation))
      Graphics.setColor(colour._1, colour._2, colour._3)
      Graphics.setLineWidth(2)
      Graphics.line(x0, y0, x1, y1)
      Graphics.setLineWidth(1)
    }

    def lifeBar(entity: LivingEntity & Body): Unit = {
      val radius = entity.shape.radius
      val (x, y) = toScreen(entity.currentPosition(now) + Complex.i * (radius + 10))
      val ratio  = math.max(0.0, math.min(1.0, entity.life / entity.maxLife))
      Graphics.setColor(1, 1, 1)
      Graphics.rectangle("fill", x - radius, y - 2.5, 2 * radius, 5)
      if (ratio > 0.5) Graphics.setColor(0, 0.8, 0)
      else if (ratio > 0.2) Graphics.setColor(1, 0.6, 0)
      else Graphics.setColor(0.9, 0, 0)
      Graphics.rectangle("fill", x - radius, y - 2.5, 2 * radius * ratio, 5)
      Graphics.setColor(0, 0, 0)
      Graphics.rectangle("line", x - radius, y - 2.5, 2 * radius, 5)
    }

    def castingBar(entity: Entity & Body): Unit =
      gameState.castingEntityInfo.get(entity.id).filter(_.isCasting(now, 0L)).foreach { info =>
        val radius   = entity.shape.radius
        val (x, y)   = toScreen(entity.currentPosition(now) - Complex.i * (radius + 10))
        val progress = (info.castingTime - info.remainingCastingTime(now, 0L)).toDouble / info.castingTime
        val colour   = info.ability.abilityColour
        Graphics.setColor(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0)
        Graphics.rectangle("fill", x - radius, y - 2.5, 2 * radius * progress, 5)
        Graphics.setColor(0, 0, 0)
        Graphics.rectangle("line", x - radius, y - 2.5, 2 * radius, 5)
      }

    Graphics.setBackgroundColor(200 / 255.0, 200 / 255.0, 200 / 255.0)

    // Origin, as in the Indigo frontend
    val (ox, oy) = toScreen(Complex.zero)
    Graphics.setColor(1, 1, 1)
    Graphics.circle("fill", ox, oy, 2)

    val entities = gameState.entities.values.toList.sortBy(_.id.toString)

    // Obstacles
    for (case obstacle: Obstacle <- entities) {
      Graphics.setColor(1, 1, 1)
      drawShape(obstacle, "fill")
      Graphics.setColor(0.6, 0.6, 0.6)
      drawShape(obstacle, "line")
    }

    // Other bodies (bullets, zones, adds, ...): outlines
    for (case body: Body <- entities) body match {
      case _: Obstacle | _: BossEntity | _: PlayerClass => ()
      case other =>
        Graphics.setColor(0.2, 0.2, 0.2)
        drawShape(other, "line")
        other match {
          case living: LivingEntity => lifeBar(living.asInstanceOf[LivingEntity & Body])
          case _                    => ()
        }
    }

    // Bosses
    for (boss <- gameState.bosses.values) {
      Graphics.setColor(1, 1, 1)
      drawShape(boss, "fill")
      Graphics.setColor(0, 0, 0)
      drawShape(boss, "line")
      nose(boss, (0, 0, 0))
      lifeBar(boss)
      castingBar(boss)
    }

    // Players
    for (player <- gameState.players.values) {
      val (r, g, b) = player.rgb
      Graphics.setColor(r / 255.0, g / 255.0, b / 255.0)
      drawShape(player, "fill")
      Graphics.setColor(0, 0, 0)
      drawShape(player, "line")
      nose(player, (1, 1, 1))
      lifeBar(player)
      castingBar(player)
    }

    hud(gameState, screen.width)
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
    Graphics.print("WASD: move    Escape: quit", 10, 64)
    val message =
      if (gameState.ended) (if (gameState.playersWon) "Victory!" else "Defeat...")
      else if (!gameState.started) "Waiting for the game to start..."
      else ""
    if (message.nonEmpty) Graphics.printf(message, 0, 100, width, "center")
  }
}
