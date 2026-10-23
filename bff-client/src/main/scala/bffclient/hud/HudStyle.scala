package bffclient.hud

import bffclient.love.{Font, Graphics}
import bffclient.ui.{Colour as Paint, Rect}
import utils.misc.RGBColour

/** The look of the HUD: dark translucent panels with rounded corners and a thin light border,
  * rounded bars on a dark track with a sheen, and white text with a shadow.
  */
object HudStyle {
  val panelColour  = Paint.rgb(52, 58, 76).withAlpha(0.8)
  val borderColour = Paint(1, 1, 1, 0.16)
  val trackColour  = Paint(0, 0, 0, 0.45)
  val textColour   = Paint.rgb(242, 243, 247)
  val mutedColour  = Paint.rgb(160, 168, 188)
  val targetColour = Paint.rgb(255, 90, 70)

  lazy val font: Font      = Graphics.newFont(12)
  lazy val smallFont: Font = Graphics.newFont(10)

  private def set(colour: Paint, alpha: Double): Unit =
    Graphics.setColor(colour.r, colour.g, colour.b, colour.a * alpha)

  def paint(colour: RGBColour): Paint = Paint.rgb(colour.red, colour.green, colour.blue)

  /** Lighter, for the colours of the game (e.g. pure blue) on the dark panels. */
  def softened(colour: RGBColour): Paint = paint(colour).lighter(0.25)

  /** Green, then orange, then red (like the frontend's `StatusBar.lifeStatusColor`). */
  def lifeColour(ratio: Double): Paint =
    if ratio > 0.5 then Paint.rgb(70, 200, 95)
    else if ratio > 0.2 then Paint.rgb(240, 165, 45)
    else Paint.rgb(225, 65, 60)

  def roundedFill(rect: Rect, radius: Double, colour: Paint, alpha: Double = 1.0): Unit = {
    set(colour, alpha)
    Graphics.rectangle("fill", rect.x, rect.y, rect.width, rect.height, radius, radius)
  }

  def roundedLine(
      rect: Rect,
      radius: Double,
      colour: Paint,
      lineWidth: Double = 1,
      alpha: Double = 1.0
  ): Unit = {
    set(colour, alpha)
    Graphics.setLineWidth(lineWidth)
    Graphics.rectangle(
      "line",
      rect.x + 0.5,
      rect.y + 0.5,
      rect.width - 1,
      rect.height - 1,
      radius,
      radius
    )
    Graphics.setLineWidth(1)
  }

  /** A panel, with a lighter top edge (lit from above) and a thin border. */
  def panel(rect: Rect, alpha: Double = 1.0, radius: Double = 6): Unit = {
    roundedFill(rect, radius, panelColour, alpha)
    set(Paint(1, 1, 1, 0.1), alpha)
    Graphics.line(rect.x + radius, rect.y + 1.5, rect.right - radius, rect.y + 1.5)
    roundedLine(rect, radius, borderColour, 1, alpha)
  }

  /** The glow around the panel of the target. */
  def targetGlow(rect: Rect, radius: Double = 6): Unit = {
    roundedLine(
      Rect(rect.x - 3, rect.y - 3, rect.width + 6, rect.height + 6),
      radius + 3,
      targetColour.withAlpha(0.3),
      4
    )
    roundedLine(rect, radius, targetColour, 2)
  }

  /** A bar: filled in `colour` up to `ratio` (between 0 and 1) on a dark track. */
  def bar(rect: Rect, ratio: Double, colour: Paint, alpha: Double = 1.0): Unit = {
    val radius = math.min(3.0, rect.height / 2)
    roundedFill(rect, radius, trackColour, alpha)
    val width = rect.width * math.max(0.0, math.min(1.0, ratio))
    if width >= 1 then {
      roundedFill(
        Rect(rect.x, rect.y, width, rect.height),
        math.min(radius, width / 2),
        colour,
        alpha
      )
      // A sheen on the upper part
      if width > 2 then {
        set(Paint(1, 1, 1, 0.2), alpha)
        Graphics.rectangle("fill", rect.x + 1, rect.y + 1, width - 2, rect.height * 0.4)
      }
    }
  }

  /** Text with a shadow, vertically centred in `rect`, in `font` (the small one in thin
    * rectangles).
    */
  def text(
      value: String,
      rect: Rect,
      align: String = "left",
      colour: Paint = textColour,
      alpha: Double = 1.0,
      padding: Double = 4
  ): Unit = {
    val f            = if rect.height < 15 then smallFont else font
    val previousFont = Graphics.getFont()
    Graphics.setFont(f)
    val y = math.round(rect.y + (rect.height - f.getHeight()) / 2).toDouble
    val x = math.round(rect.x + padding).toDouble
    set(Paint(0, 0, 0, 0.7), alpha)
    Graphics.printf(value, x + 1, y + 1, rect.width - 2 * padding, align)
    set(colour, alpha)
    Graphics.printf(value, x, y, rect.width - 2 * padding, align)
    Graphics.setFont(previousFont)
  }

  def ratio(value: Double, max: Double): Double =
    if max <= 0 then 0 else math.max(0.0, math.min(1.0, value / max))
}
