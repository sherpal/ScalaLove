package bffclient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import bffclient.ui.*
import gamelogic.docs.BossMetadata
import menus.data.{MenuGameComm, User}
import models.bff.outofgame.{MenuGameWithPlayers, PlayerClasses}
import models.bff.outofgame.gameconfig.*
import models.users.LoginUser
import utils.misc.RGBColour

/** A screen of the menus, drawn every frame. */
trait Screen {
  def draw(ui: Ui): Unit

  /** When the menus switch to another screen. */
  def leave(): Unit = ()
}

/** The menus, drawn with [[Ui]] like the frontend's pages: login, games, then the game's lobby,
  * until the game server credentials arrive and `startGame` takes over. The game comes back to the
  * menus with the function it is given.
  */
final class MenuApp(val api: MenuApi, startGame: (GameAccess, GameExit => Unit) => Unit)(using
    ExecutionContext) {
  val ui                     = new Ui
  var preferences            = MenuPreferences.load()
  private var screen: Screen = new LoginScreen(this)
  private var notification: Option[(String, Boolean, Double)] = None

  def install(): Unit = {
    Keyboard.setKeyRepeat(true)
    Love.draw = () => {
      Graphics.setBackgroundColor(Theme.background.r, Theme.background.g, Theme.background.b)
      ui.frame { screen.draw(ui); drawNotification() }
    }
    Love.mousepressed = (x, y, button, _, _) => ui.mousePressed(x, y, button)
    Love.mousereleased = (x, y, button, _, _) => ui.mouseReleased(x, y, button)
    Love.textinput = text => ui.textInput(text)
    Love.keypressed = (_, scancode, _) => ui.keyPressed(scancode)
  }

  def show(next: Screen): Unit = { screen.leave(); screen = next }

  def size: (Double, Double) = {
    val dimensions = Graphics.getDimensions()
    (dimensions.width.toDouble, dimensions.height.toDouble)
  }

  def notify(message: String, isError: Boolean = false): Unit = {
    println(s"[menu] $message")
    notification = Some((message, isError, Timer.getTime() + 5))
  }

  /** Calls `onSuccess` with the result, or notifies the failure (and calls `onFailure`). */
  def attempt[A](future: Future[A])(onSuccess: A => Unit, onFailure: Throwable => Unit = _ => ()): Unit =
    future.onComplete {
      case Success(a) => onSuccess(a)
      case Failure(error) =>
        notify(error.getMessage, isError = true)
        onFailure(error)
    }

  def updatePreferences(f: MenuPreferences => MenuPreferences): Unit = {
    preferences = f(preferences)
    preferences.save()
  }

  /** The lobby websocket stays open during the game: the menu server kicks the players who leave it. */
  def gameStarts(access: GameAccess, user: User, lobby: Option[Lobby]): Unit = {
    screen = new Screen { def draw(ui: Ui): Unit = ui.loading("Connecting to the game server", 40, 40) }
    startGame(access, exit => backToMenus(user, access.gameId, lobby, exit))
  }

  /** The menu server never ends games by itself (players stay "already playing" until it sees them
    * disconnected from the lobby for a while), so we leave the game, which deletes it for its creator.
    */
  private def backToMenus(user: User, gameId: String, lobby: Option[Lobby], exit: GameExit): Unit = {
    Love.update = null
    Love.keyreleased = null
    install()
    exit match {
      case GameExit.Ended =>
        // Fails when another player stopped it already
        api.stopGameServer(gameId).failed.foreach(failure => println(s"[menu] Game server not stopped: ${failure.getMessage}"))
      case GameExit.ConnectionLost       => notify("The connection to the game server was lost", isError = true)
      case GameExit.CannotConnect(error) => notify(s"Cannot connect to the game server: $error", isError = true)
    }
    lobby.foreach(_.close())
    screen = new Screen { def draw(ui: Ui): Unit = ui.loading("Leaving the game", 40, 40) }
    api.leaveGame(gameId).onComplete { result =>
      result.failed.foreach(failure => notify(s"Could not leave the game: ${failure.getMessage}", isError = true))
      show(new GamesScreen(this, user))
    }
  }

  private def drawNotification(): Unit = notification.foreach { (message, isError, until) =>
    if (Timer.getTime() > until) notification = None
    else {
      val (width, height) = size
      val textWidth       = ui.textWidth(message)
      val rect            = Rect((width - textWidth) / 2 - 20, height - 110, textWidth + 40, 40)
      ui.fill(rect, if (isError) Theme.negative else Theme.positive)
      ui.textIn(message, rect)
    }
  }
}

/** Loads data, one request at a time: a refresh asked during a request is done after it. */
final class Refresh[A](app: MenuApp, load: () => Future[A])(onLoaded: A => Unit)(using ExecutionContext) {
  private var loading = false
  private var again   = false

  def apply(): Unit =
    if (loading) again = true
    else {
      loading = true
      app.attempt(load())(
        a => { done(); onLoaded(a) },
        _ => done()
      )
    }

  private def done(): Unit = {
    loading = false
    if (again) { again = false; apply() }
  }
}

final class LoginScreen(app: MenuApp)(using ExecutionContext) extends Screen {
  private val name           = TextInput(app.preferences.userName, maxLength = 20)
  private var busy           = false
  private var error          = Option.empty[String]
  app.ui.setFocus(name)

  def draw(ui: Ui): Unit = {
    val (width, height) = app.size
    val box             = Rect(width / 2 - 220, height / 2 - 120, 440, 210)
    ui.textIn("Welcome to Battle for Flatland!", Rect(0, box.y - 90, width, 50), Theme.title)
    ui.panel(box)
    ui.text("Please choose a pseudo", box.x + 30, box.y + 28)
    val entered = ui.textField(name, Rect(box.x + 30, box.y + 56, 380, 38), "Your pseudo", enabled = !busy)
    error.foreach(message => ui.text(message, box.x + 30, box.y + 102, Theme.small, Theme.negative))
    if (busy) ui.loading("Logging in", box.x + 30, box.y + 150)
    val clicked = ui.button("LFG!", Rect(box.right - 150, box.y + 140, 120, 40), enabled = !busy, ButtonStyle.Primary)
    if ((entered || clicked) && !busy) login()
    if (ui.wasPressed("escape")) Event.quit(0)
  }

  private def login(): Unit = {
    val pseudo = name.text.trim
    LoginUser.validator.validate(LoginUser(pseudo, "1234")).get("name") match {
      case Some(errors) => error = Some(s"Pseudo: ${errors.map(_.repr).mkString(", ")}")
      case None =>
        busy = true
        error = None
        app.api.login(pseudo).onComplete {
          case Success(user) =>
            app.updatePreferences(_.copy(userName = user.name))
            app.show(new GamesScreen(app, user))
          case Failure(failure) =>
            busy = false
            error = Some(failure.getMessage)
        }
    }
  }
}

/** The games that did not start yet, like the frontend's `GameTable`. */
final class GamesScreen(app: MenuApp, user: User)(using ExecutionContext) extends Screen {
  private var games         = Option.empty[Vector[MenuGameWithPlayers]]
  private val refresh       = Refresh(app, () => app.api.games)(received)
  private var lobby         = Option.empty[Lobby]
  private var busy          = false
  private val defaultName   = s"${user.name}'s game"
  private val newGameName   = TextInput(defaultName, maxLength = 32)

  private def received(all: Vector[MenuGameWithPlayers]): Unit = {
    games = Some(all.filterNot(_.started))
    // Names are unique, and started games (not shown) keep theirs
    if (newGameName.text.startsWith(defaultName)) {
      val taken = all.map(_.game.gameName).toSet
      newGameName.text = (Iterator.single(defaultName) ++ Iterator.from(2).map(i => s"$defaultName $i")).find(!taken(_)).get
    }
  }

  refresh()
  // Any change on the server is announced on the lobby websocket
  app.attempt(app.api.lobby(None)(_ => refresh()))(opened => lobby = Some(opened))

  override def leave(): Unit = lobby.foreach(_.close())

  def draw(ui: Ui): Unit = {
    val (width, height) = app.size
    ui.text("Battle for Flatland", 40, 30, Theme.title)
    ui.textIn(s"Welcome, ${user.name}", Rect(0, 40, width - 40, 30), Theme.body, Theme.muted, "right")

    val gameYouAreIn = games.flatMap(_.find(_.containsPlayer(user)))
    gameYouAreIn match {
      case Some(game) =>
        val strip = Rect(40, 100, width - 80, 50)
        ui.fill(strip, Theme.primary.withAlpha(0.25))
        ui.textIn(s"You are currently involved in the game '${game.game.gameName}'.",
          Rect(strip.x + 16, strip.y, strip.width, strip.height), align = "left")
        if (ui.button("Join it back", Rect(strip.right - 150, strip.y + 7, 140, 36), !busy, ButtonStyle.Primary))
          enter(game.id)
      case None if games.isDefined =>
        val entered = ui.textField(newGameName, Rect(40, 105, 320, 40), "Game name", enabled = !busy)
        if ((ui.button("Create game", Rect(370, 105, 150, 40), !busy, ButtonStyle.Primary) || entered) && !busy)
          create()
      case None => ()
    }

    val table   = Rect(40, 180, width - 80, height - 220)
    val columns = Seq("Name" -> 0.0, "Created by" -> 0.4, "Players" -> 0.65)
    ui.panel(table)
    for ((title, at) <- columns) ui.text(title, table.x + 16 + at * table.width, table.y + 12, colour = Theme.muted)
    games match {
      case None => ui.loading("Loading the games", table.x + 16, table.y + 50)
      case Some(Vector()) =>
        ui.text("There are currently no games. Go ahead and create one!", table.x + 16, table.y + 50)
      case Some(all) =>
        val rowsThatFit = ((table.height - 50) / 44).toInt
        for ((game, i) <- all.take(rowsThatFit).zipWithIndex) {
          val row = Rect(table.x + 1, table.y + 44 + 44 * i, table.width - 2, 44)
          if (i % 2 == 0) ui.fill(row, Theme.background.withAlpha(0.35))
          val cells = Seq(game.game.gameName, game.game.gameCreator.name, game.players.length.toString)
          for ((cell, (_, at)) <- cells.zip(columns))
            ui.textIn(cell, Rect(table.x + 16 + at * table.width, row.y, table.width * 0.25, row.height), align = "left")
          // Like the frontend: one may join any game, unless already in one (which may then be joined back)
          val canJoin = gameYouAreIn.forall(_.id == game.id)
          if (canJoin && ui.button(if (gameYouAreIn.isDefined) "Join back" else "Join",
                Rect(row.right - 130, row.y + 5, 115, 34), !busy))
            if (gameYouAreIn.isDefined) enter(game.id) else join(game.id)
        }
        if (all.size > rowsThatFit)
          ui.text(s"... and ${all.size - rowsThatFit} more", table.x + 16, table.bottom - 28, Theme.small, Theme.muted)
    }
  }

  private def create(): Unit = {
    busy = true
    app.attempt(app.api.createGame(newGameName.text.trim))(game => enter(game.id), _ => busy = false)
  }

  private def join(gameId: String): Unit = {
    busy = true
    app.attempt(app.api.joinGame(gameId))(_ => enter(gameId), _ => busy = false)
  }

  private def enter(gameId: String): Unit = app.show(new GameLobbyScreen(app, user, gameId))
}

/** The settings of a game before it starts, like the frontend's `GameSettings`: boss, players,
  * one's class, colour and readiness. The game creator adds AIs and launches the game.
  */
final class GameLobbyScreen(app: MenuApp, user: User, gameId: String)(using ExecutionContext) extends Screen {
  private var game        = Option.empty[MenuGameWithPlayers]
  private var lobby       = Option.empty[Lobby]
  private var busy        = false
  private var launching   = false
  private var startingSince = Option.empty[Double]
  private def starting      = startingSince.isDefined
  private var gameStarted = false
  private var left        = false
  private var preferencesApplied = false
  private val refresh = Refresh(app, () => app.api.game(gameId))(received)

  refresh()
  app.attempt(app.api.lobby(Some(gameId)) {
    case MenuGameComm.DataUpdated() => refresh()
    case MenuGameComm.GameStarted() => startingSince = Some(Timer.getTime())
    case MenuGameComm.HereAreYourCredentials(id, secret, port) =>
      gameStarted = true
      app.gameStarts(GameAccess(app.api.host, port, user.name, id, secret), user, lobby)
  })(opened => lobby = Some(opened))

  // Once the game started, the lobby websocket is closed by MenuApp when coming back to the menus
  override def leave(): Unit = {
    left = true
    if (!gameStarted) lobby.foreach(_.close())
  }

  private def received(maybeGame: Option[MenuGameWithPlayers]): Unit = maybeGame match {
    case _ if left || gameStarted => () // answers to requests made before
    case None =>
      app.notify("The game was closed by its creator", isError = true)
      app.show(new GamesScreen(app, user))
    case Some(g) if !g.containsPlayer(user) =>
      app.notify("You are not in this game (anymore)", isError = true)
      app.show(new GamesScreen(app, user))
    case Some(g) =>
      game = Some(g)
      if (!preferencesApplied) { preferencesApplied = true; applyPreferences(g) }
  }

  private def config: Option[GameConfiguration]  = game.map(_.game.gameConfiguration)
  private def me: Option[PlayerInfo]              = config.flatMap(_.playersInfo.get(user.name))
  private def amICreator: Boolean                 = game.exists(_.isGameCreator(user))
  private def boss: Option[BossMetadata]          = config.flatMap(c => BossMetadata.maybeMetadataByName(c.bossName))

  /** Like the frontend, the last class, colour (and boss for the creator) are chosen again. */
  private def applyPreferences(g: MenuGameWithPlayers): Unit = {
    val preferences = app.preferences
    for (info <- me) {
      val wanted = info
        .withClass(preferences.playerClass.orElse(info.maybePlayerClass))
        .copy(maybePlayerColour = preferences.colour.orElse(info.maybePlayerColour))
        .withReadyState(PlayerStatus.NotReady)
      if (wanted != info) changeMe(_ => wanted)
    }
    for (bossName <- preferences.bossName if amICreator && bossName != g.game.gameConfiguration.bossName)
      changeBoss(bossName)
  }

  private def changeMe(f: PlayerInfo => PlayerInfo): Unit = me.foreach { info =>
    val changed = f(info)
    busy = true
    app.attempt(app.api.changePlayerInfo(gameId, changed))(
      updated => {
        busy = false
        game = Some(updated)
        app.updatePreferences(_.copy(playerClass = changed.maybePlayerClass, colour = changed.maybePlayerColour))
      },
      _ => { busy = false; refresh() }
    )
  }

  private def changeBoss(bossName: String): Unit = {
    busy = true
    app.attempt(app.api.changeGameConfig(gameId, GameConfiguration.GameConfigMetadata(bossName)))(
      updated => { busy = false; game = Some(updated); app.updatePreferences(_.copy(bossName = Some(bossName))) },
      _ => busy = false
    )
  }

  private def addAIs(count: Int): Unit = {
    busy = true
    val added = (1 to count).foldLeft(Future.unit)((previous, _) => previous.flatMap(_ => app.api.addAI(gameId).map(_ => ())))
    app.attempt(added)(_ => busy = false, _ => busy = false)
  }

  def draw(ui: Ui): Unit = game match {
    case None    => ui.loading("Loading the game", 40, 40)
    case Some(g) => drawGame(ui, g)
  }

  private def drawGame(ui: Ui, g: MenuGameWithPlayers): Unit = {
    val (width, height) = app.size
    val configuration   = g.game.gameConfiguration
    val enabled         = !busy && !starting
    val left            = 40.0
    val leftWidth       = width * 0.6
    val right           = left + leftWidth + 30
    val rightWidth      = width - right - 40

    ui.text(g.game.gameName, left, 26, Theme.large)
    ui.text(s"Created by ${g.game.gameCreator.name} - game ${g.id}", left, 58, Theme.small, Theme.muted)
    ui.textIn(s"Logged in as ${user.name}", Rect(0, 30, width - 40, 30), Theme.body, Theme.muted, "right")

    // Players
    val players = configuration.playersInfo.values.toVector.sortBy(info => (info.playerName.name != user.name, info.playerName.name))
    val table   = Rect(left, 90, leftWidth, 44 + 40 * math.max(players.size, 1))
    ui.panel(table)
    val columns = Seq("Name" -> 0.0, "Class" -> 0.35, "Colour" -> 0.58, "Ready" -> 0.72)
    for ((title, at) <- columns) ui.text(title, table.x + 16 + at * table.width, table.y + 12, colour = Theme.muted)
    for ((info, i) <- players.zipWithIndex) {
      val row = Rect(table.x + 1, table.y + 42 + 40 * i, table.width - 2, 40)
      if (i % 2 == 0) ui.fill(row, Theme.background.withAlpha(0.35))
      def cell(at: Double) = Rect(table.x + 16 + at * table.width, row.y, table.width * 0.2, row.height)
      val name = info.playerName match {
        case PlayerName.HumanPlayerName(name) => if (name == user.name) s"$name (you)" else name
        case ai: PlayerName.AIPlayerName      => s"${ai.name} (AI)"
      }
      ui.textIn(name, cell(columns(0)._2), align = "left")
      ui.textIn(info.maybePlayerClass.fold("Not chosen")(_.value), cell(columns(1)._2), align = "left",
        colour = if (info.maybePlayerClass.isDefined) Theme.text else Theme.muted)
      info.maybePlayerColour match {
        case Some(colour) => ui.swatch(MenuColours.toUi(colour), Rect(cell(columns(2)._2).x, row.y + 9, 22, 22), enabled = false)
        case None         => ui.textIn("-", cell(columns(2)._2), align = "left", colour = Theme.muted)
      }
      ui.textIn(if (info.isReady) "Ready" else "Not ready", cell(columns(3)._2), align = "left",
        colour = if (info.isReady) Theme.positive.lighter(0.3) else Theme.negative.lighter(0.3))
      if (amICreator && info.playerName.name != user.name &&
          ui.button("Kick", Rect(row.right - 80, row.y + 5, 70, 30), enabled, ButtonStyle.Danger)) {
        busy = true
        app.attempt(app.api.kickPlayer(gameId, info.playerName.name))(_ => busy = false, _ => busy = false)
      }
    }

    // My settings
    var y = table.bottom + 20
    for (info <- me) {
      val panel = Rect(left, y, leftWidth, 150)
      ui.panel(panel)
      ui.text("Class", panel.x + 16, panel.y + 18, colour = Theme.muted)
      ui.segmented(PlayerClasses.allChoices, info.maybePlayerClass, Rect(panel.x + 100, panel.y + 10, 420, 36),
        (_: PlayerClasses).value, enabled).foreach(cls => changeMe(_.withClass(Some(cls))))
      ui.text("Colour", panel.x + 16, panel.y + 64, colour = Theme.muted)
      for ((colour, i) <- MenuColours.palette.zipWithIndex)
        if (ui.swatch(MenuColours.toUi(colour), Rect(panel.x + 100 + 30 * i, panel.y + 60, 24, 24),
            info.maybePlayerColour.contains(colour), enabled))
          changeMe(_.withColour(colour))
      if (ui.checkbox(info.isReady, "Ready", panel.x + 16, panel.y + 108, enabled))
        changeMe(_.withReadyState(if (info.isReady) PlayerStatus.NotReady else PlayerStatus.Ready))
      y = panel.bottom + 20
    }

    // What prevents the game to start
    val problems = configuration.validate.toVector.sortBy(_._1)
    if (problems.isEmpty) ui.text("Everyone is ready and well set, LFG", left, y, colour = Theme.positive.lighter(0.3))
    else {
      ui.text("The following things prevent the game to start:", left, y)
      for (((name, errors), i) <- problems.zipWithIndex)
        ui.text(s"$name: ${errors.mkString(", ")}", left + 16, y + 24 + 20 * i, Theme.small, Theme.muted)
    }

    // Boss
    val bossPanel = Rect(right, 90, rightWidth, height - 180)
    ui.panel(bossPanel)
    ui.text("Boss", bossPanel.x + 16, bossPanel.y + 14, colour = Theme.muted)
    if (amICreator)
      ui.selector(BossMetadata.allBossNames, configuration.bossName, Rect(bossPanel.x + 70, bossPanel.y + 8, bossPanel.width - 86, 34),
        enabled = enabled).foreach(changeBoss)
    else ui.text(configuration.bossName, bossPanel.x + 70, bossPanel.y + 14)
    boss.foreach { metadata =>
      val inner = bossPanel.width - 32
      ui.text(s"For ${metadata.intendedFor} player(s), ${metadata.maxLife.toInt} life points", bossPanel.x + 16,
        bossPanel.y + 56, Theme.small, Theme.muted)
      var textY = bossPanel.y + 84
      textY += ui.paragraph(metadata.description, bossPanel.x + 16, textY, inner, Theme.small) + 12
      for (doc <- metadata.abilitiesDoc if textY < bossPanel.bottom - 40) {
        ui.text(doc.name, bossPanel.x + 16, textY, Theme.small, Theme.primary.lighter(0.4))
        textY += 18 + ui.paragraph(doc.description, bossPanel.x + 16, textY + 18, inner, Theme.small, Theme.muted) + 8
      }
    }

    // Actions
    val bar = height - 70
    if (ui.button("Leave game", Rect(left, bar, 150, 44), enabled)) leaveGame()
    if (amICreator) {
      // Like the menu server, which picks the classes of the AIs: all the players' classes count
      val classes = configuration.playersInfo.values.flatMap(_.maybePlayerClass).toVector
      val toFill  = boss.flatMap(_.fillWithAI(classes)).fold(0)(_.size)
      if (ui.button("Add AI", Rect(width - 560, bar, 150, 44), enabled && toFill > 0)) addAIs(1)
      if (ui.button("Fill with AIs", Rect(width - 400, bar, 170, 44), enabled && toFill > 0)) addAIs(toFill)
      if (ui.button("Launch game!", Rect(width - 220, bar, 180, 44), enabled && !launching && g.game.gameConfigurationIsValid,
          ButtonStyle.Positive)) {
        launching = true
        app.attempt(app.api.startGame(gameId))(_ => (), _ => launching = false)
      }
    } else ui.textIn("Waiting for the game creator to launch the game", Rect(0, bar, width - 40, 44), colour = Theme.muted,
      align = "right")

    for (since <- startingSince) {
      ui.fill(Rect(0, 0, width, height), Theme.background.withAlpha(0.8))
      ui.loading("Game is starting, please wait for setup to finish", width / 2 - 240, height / 2)
      if (Timer.getTime() - since > 30) {
        ui.textIn("The game server did not answer.", Rect(0, height / 2 + 40, width, 30), colour = Theme.muted)
        if (ui.button("Leave game", Rect(width / 2 - 75, height / 2 + 80, 150, 44), !busy)) leaveGame()
      }
    }
  }

  private def leaveGame(): Unit = {
    busy = true
    app.attempt(app.api.leaveGame(gameId))(_ => app.show(new GamesScreen(app, user)), _ => busy = false)
  }
}

object MenuColours {
  val palette: Vector[RGBColour] = RGBColour.someColours

  def toUi(colour: RGBColour): Colour = Colour.rgb(colour.red, colour.green, colour.blue)
}
