package bffclient

import bffclient.ui.*

/** Shown over the game once it ended, or when the connection to the game server is lost: back to
  * the menus (when the game was joined through them) or quit.
  */
final class EndOfGame(client: GameClient, backToMenus: Option[() => Unit]) {

  def draw(ui: Ui): Unit = {
    val screen          = Graphics.getDimensions()
    val (width, height) = (screen.width.toDouble, screen.height.toDouble)
    val gameState       = client.gameState
    ui.fill(Rect(0, 0, width, height), Theme.background.withAlpha(0.5))
    val box = Rect(width / 2 - 230, height / 2 - 110, 460, 220)
    ui.panel(box)

    val (title, colour) =
      if (!gameState.ended) ("Connection lost", Theme.negative.lighter(0.3))
      else if (gameState.playersWon) ("Victory!", Theme.positive.lighter(0.3))
      else ("Defeat...", Theme.negative.lighter(0.3))
    ui.textIn(title, Rect(box.x, box.y + 20, box.width, 50), Theme.title, colour)
    val subtitle = (gameState.startTime, gameState.endTime) match {
      case (Some(start), Some(end)) =>
        val seconds = ((end - start) / 1000).toInt
        f"The fight lasted ${seconds / 60}%d:${seconds % 60}%02d"
      case _ if !gameState.ended => "The game server closed the connection"
      case _ => ""
    }
    ui.textIn(subtitle, Rect(box.x, box.y + 78, box.width, 30), colour = Theme.muted)

    val buttons = Rect(box.x + 30, box.bottom - 70, box.width - 60, 44)
    backToMenus match {
      case Some(back) =>
        val clicked = ui.button(
          "Back to menus",
          Rect(buttons.x, buttons.y, 250, buttons.height),
          style = ButtonStyle.Primary
        )
        if (clicked || ui.wasPressed("return") || ui.wasPressed("kpenter")) back()
        if (
          ui.button("Quit", Rect(buttons.right - 140, buttons.y, 140, buttons.height)) || ui
            .wasPressed("escape")
        )
          client.quit()
      case None =>
        if (
          ui.button(
            "Quit",
            Rect(buttons.x, buttons.y, buttons.width, buttons.height),
            style = ButtonStyle.Primary
          ) ||
          ui.wasPressed("escape") || ui.wasPressed("return")
        )
          client.quit()
    }
  }
}

/** The game menu, opened with Escape during the game. The game cannot be paused (it is played
  * online), so it goes on meanwhile.
  */
final class GameMenu(client: GameClient, leaveGame: Option[() => Unit]) {

  def draw(ui: Ui): Unit = {
    val screen          = Graphics.getDimensions()
    val (width, height) = (screen.width.toDouble, screen.height.toDouble)
    ui.fill(Rect(0, 0, width, height), Theme.background.withAlpha(0.5))
    val buttonCount = if (leaveGame.isDefined) 3 else 2
    val box = Rect(width / 2 - 180, height / 2 - 70 - 30 * buttonCount, 360, 140 + 60 * buttonCount)
    ui.panel(box)
    ui.textIn("Menu", Rect(box.x, box.y + 16, box.width, 50), Theme.title)
    ui.textIn(
      "The game goes on meanwhile",
      Rect(box.x, box.y + 66, box.width, 24),
      Theme.small,
      Theme.muted
    )

    def button(index: Int, label: String, style: ButtonStyle) =
      ui.button(
        label,
        Rect(box.x + 40, box.y + 110 + 60 * index, box.width - 80, 44),
        style = style
      )

    if (button(0, "Resume", ButtonStyle.Primary) || ui.wasPressed("escape")) client.menuOpen = false
    leaveGame.foreach(leave => if (button(1, "Leave game", ButtonStyle.Normal)) leave())
    if (button(buttonCount - 1, "Quit", ButtonStyle.Danger)) client.quit()
  }
}
