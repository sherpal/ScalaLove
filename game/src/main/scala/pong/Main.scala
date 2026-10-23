package pong

import love.*

final case class Vec(x: Double, y: Double) {
  def +(o: Vec): Vec    = Vec(x + o.x, y + o.y)
  def *(k: Double): Vec = Vec(x * k, y * k)
}

sealed trait Side
case object LeftSide extends Side
case object RightSide extends Side

final class Paddle(val side: Side, var y: Double) {
  val width  = 12.0
  val height = 80.0

  def x(screenWidth: Double): Double = side match {
    case LeftSide => 20.0
    case RightSide => screenWidth - 20.0 - width
  }

  def move(dy: Double, screenHeight: Double): Unit =
    y = Math.max(0.0, Math.min(screenHeight - height, y + dy))
}

final class Pong(width: Double, height: Double) {
  private val radius      = 8.0
  private val paddleSpeed = 400.0

  private var ball       = Vec(width / 2, height / 2)
  private var velocity   = Vec(260.0, 180.0)
  private val left       = new Paddle(LeftSide, height / 2 - 40)
  private val right      = new Paddle(RightSide, height / 2 - 40)
  private var scoreLeft  = 0
  private var scoreRight = 0

  def update(dt: Double): Unit = {
    if (Keyboard.isDown("w")) left.move(-paddleSpeed * dt, height)
    if (Keyboard.isDown("s")) left.move(paddleSpeed * dt, height)

    // Simple AI for the right paddle
    val target = ball.y - right.height / 2
    val diff   = target - right.y
    right.move(Math.max(-paddleSpeed * 0.8 * dt, Math.min(paddleSpeed * 0.8 * dt, diff)), height)

    ball = ball + velocity * dt

    if (ball.y - radius < 0 || ball.y + radius > height)
      velocity = velocity.copy(y = -velocity.y)

    for (p <- List(left, right)) bounce(p)

    if (ball.x < 0) scored(RightSide)
    else if (ball.x > width) scored(LeftSide)
  }

  private def bounce(p: Paddle): Unit = {
    val px  = p.x(width)
    val hit =
      ball.x + radius > px && ball.x - radius < px + p.width &&
        ball.y + radius > p.y && ball.y - radius < p.y + p.height
    if (hit) {
      val goingTowards = p.side match {
        case LeftSide => velocity.x < 0
        case RightSide => velocity.x > 0
      }
      if (goingTowards) velocity = Vec(-velocity.x * 1.05, velocity.y)
    }
  }

  private def scored(side: Side): Unit = {
    side match {
      case LeftSide => scoreLeft += 1
      case RightSide => scoreRight += 1
    }
    ball = Vec(width / 2, height / 2)
    velocity = Vec(if (side == LeftSide) 260.0 else -260.0, 180.0)
  }

  def draw(): Unit = {
    Graphics.setColor(1, 1, 1)
    for (p <- List(left, right))
      Graphics.rectangle("fill", p.x(width), p.y, p.width, p.height)
    Graphics.circle("fill", ball.x, ball.y, radius)
    Graphics.line(width / 2, 0, width / 2, height)
    Graphics.printf(s"$scoreLeft   $scoreRight", 0, 20, width, "center")
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val screen = Graphics.getDimensions()
    val game   = new Pong(screen.width.toDouble, screen.height.toDouble)
    Love.update = dt => game.update(dt)
    Love.draw = () => game.draw()
    Love.keypressed = (key, _, _) => if (key == "escape") Event.quit()
  }
}
