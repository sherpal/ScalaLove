package bffclient

/** An image, or a sheet of `rows` x `columns` animation frames. */
final class Sprite(image: Image, rows: Int = 1, columns: Int = 1) {
  val frameWidth: Double  = image.getWidth() / columns
  val frameHeight: Double = image.getHeight() / rows

  private val frames: Vector[Quad] = for {
    row    <- (0 until rows).toVector
    column <- 0 until columns
  } yield Graphics.newQuad(column * frameWidth, row * frameHeight, frameWidth, frameHeight, image.getWidth(),
    image.getHeight())

  def frameCount: Int = frames.size

  /** Draws the frame centred at (x, y) on screen, turned by `rotation` (counterclockwise, like in the
    * game), `width` pixels wide (and as high as the frame's proportions say, unless given). The
    * current colour tints it.
    */
  def draw(x: Double, y: Double, rotation: Double, width: Double, height: Double = -1, frame: Int = 0): Unit = {
    val scaleX = width / frameWidth
    val scaleY = if (height < 0) scaleX else height / frameHeight
    Graphics.draw(image, frames(frame % frames.size), x, y, -rotation, scaleX, scaleY, frameWidth / 2, frameHeight / 2)
  }
}

/** The frontend's images (copied from the BFF checkout by `bffClient/luaLink`), when they exist. */
object Sprites {
  private def load(path: String, rows: Int = 1, columns: Int = 1): Option[Sprite] =
    Option(Images.load(s"assets/$path")).map(Sprite(_, rows, columns))

  /** By class name (`PlayerClasses.value`): white shapes, tinted with the player's colour. */
  lazy val playerClasses: Map[String, Sprite] =
    Seq("Triangle", "Square", "Pentagon", "Hexagon").flatMap { name =>
      load(s"players/${name.toLowerCase}.png").map(name -> _)
    }.toMap

  /** Sparks in the hands of a player casting an ability (6 x 5 frames, tinted with its colour). */
  lazy val casting: Option[Sprite] = load("abilities/player-casting-ability-animation.png", rows = 6, columns = 5)

  /** The square's cleave (3 frames). */
  lazy val cleave: Option[Sprite] = load("abilities/cleave-animation.png", columns = 3)

  /** The sword of the triangle's direct hit. */
  lazy val sword: Option[Sprite] = load("abilities/sword.png")
}
