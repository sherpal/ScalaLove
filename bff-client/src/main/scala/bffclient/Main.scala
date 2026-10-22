package bffclient

import java.nio.ByteBuffer

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import boopickle.Default.*
import communication.BFFPicklers.*
import gamelogic.abilities.Ability
import gamelogic.entities.{Body, Entity, LivingEntity}
import gamelogic.gameextras.GameMarkerInfo
import gamelogic.gamestate.gameactions.markers.UpdateMarker
import gamelogic.entities.classes.Constants
import gamelogic.gamestate.{ActionGatherer, GameAction, GameState, GreedyActionGatherer}
import gamelogic.gamestate.gameactions.MovingBodyMoves
import gamelogic.physics.Complex
import models.bff.ingame.{Controls, InGameWSProtocol, UserInput}
import models.bff.outofgame.PlayerClasses
import models.bff.ingame.InGameWSProtocol.*
import bffclient.ui.Ui

/** Connects to a real Battle for Flatland game server, plays the start-of-game handshake like the
  * frontend, keeps a client-side copy of the game state from the server's updates, and renders it.
  *
  * Configuration via environment variables: BFF_HOST, BFF_PORT, BFF_USER, BFF_SECRET, BFF_GAME_ID.
  * With BFF_HEADLESS set, there is no window: the client walks a bit, logs, and quits at the end.
  */
object Main {
  given ExecutionContext = ExecutionContext.global

  private def env(name: String, default: String): String = Option(Os.getenv(name)).getOrElse(default)

  /** With BFF_MENU_URL (e.g. http://127.0.0.1:9000), goes through the menu server first: with the
    * menus' UI, or scripted when headless (BFF_USER, BFF_CLASS, BFF_BOSS). Otherwise, connects
    * directly to a game server (BFF_HOST, BFF_PORT, BFF_SECRET, BFF_GAME_ID).
    */
  def main(args: Array[String]): Unit = {
    val user     = env("BFF_USER", "alice")
    val headless = Os.getenv("BFF_HEADLESS") != null
    Option(Os.getenv("BFF_MENU_URL")) match {
      case Some(menuUrl) =>
        val (host, port) = menuUrl.stripPrefix("http://").split(":") match {
          case Array(h, p) => (h, p.toInt)
          case Array(h)    => (h, 80)
          case _           => sys.error(s"Invalid BFF_MENU_URL $menuUrl")
        }
        val api = new MenuApi(host, port)
        if (headless) {
          val playerClass = PlayerClasses.playerClassByName(env("BFF_CLASS", "Triangle")).getOrElse(PlayerClasses.Triangle)
          val menu        = new MenuSession(api, user, playerClass, env("BFF_BOSS", "Boss 101"))
          menu.run().onComplete {
            case Success(access) => startGame(access, headless)
            case Failure(error) =>
              menu.info(s"ERROR: ${error.getMessage}")
              Event.quit(1)
          }
        } else new MenuApp(api, (access, backToMenus) => startGame(access, headless, Some(backToMenus))).install()
      case None =>
        startGame(
          GameAccess(env("BFF_HOST", "localhost"), env("BFF_PORT", "22223").toInt, user,
            env("BFF_GAME_ID", "game"), env("BFF_SECRET", "secret")),
          headless
        )
    }
  }

  /** Connects to the game server and runs the game. When the game was joined through the menus,
    * `backToMenus` goes back to them.
    */
  def startGame(access: GameAccess, headless: Boolean, backToMenus: Option[GameExit => Unit] = None): Unit = {
    val url = s"ws://${access.host}:${access.port}/ws/connect" +
      s"?userName=${access.userName}&secret=${access.secret}&gameId=${access.gameId}"
    println(s"Connecting to $url")
    val connection = WebSocketModule.connect(url)
    if (connection.socket == null) {
      println(s"Connection failed: ${connection.error}")
      backToMenus match {
        case Some(back) => back(GameExit.CannotConnect(connection.error))
        case None       => Event.quit(1)
      }
    } else {
      println("Connected")
      val (controls, controlsInfo) = ControlsFile.load()
      println(controlsInfo)
      val lag = env("BFF_LAG_MS", "0").toDouble / 1000
      if (lag > 0) println(s"Simulated latency: ${lag * 1000} ms round trip")
      if headless then {
        val client = new GameClient(connection.socket, access.userName, headless, controls, controlsInfo, () => Complex.zero,
          () => None, lag)
        Love.update = dt => client.update(dt)
      } else {
        var renderer: Renderer = null
        val client = new GameClient(connection.socket, access.userName, headless, controls, controlsInfo, () => renderer.mouseWorld,
          () => renderer.hud.hovered, lag)
        renderer = new Renderer(client)
        // While an overlay is shown (game menu, end of game), the inputs go to it
        val ui          = new Ui
        val leave       = backToMenus.map(back => () => { val exit = client.exit; client.leave(); back(exit) })
        val gameMenu    = new GameMenu(client, leave)
        val endOfGame   = new EndOfGame(client, leave)
        def overlayShown = client.finished || client.menuOpen
        Love.update = dt => client.update(dt)
        Love.draw = () => {
          renderer.draw()
          if (client.finished) ui.frame(endOfGame.draw(ui))
          else if (client.menuOpen) ui.frame(gameMenu.draw(ui))
        }
        Love.keypressed = (_, scancode, isRepeat) =>
          if (overlayShown) ui.keyPressed(scancode) else if (!isRepeat) client.keyPressed(scancode)
        Love.keyreleased = (_, scancode) => if (!overlayShown) client.keyReleased(scancode)
        Love.mousepressed = (x, y, button, _, _) =>
          if (overlayShown) ui.mousePressed(x, y, button)
          else if (button == 1) renderer.hud.targetAt(x, y) match {
            case Some(id) => client.selectTarget(id) // a frame of the HUD
            case None     => client.clicked(renderer.toWorld(x, y))
          }
        Love.mousereleased = (x, y, button, _, _) =>
          if (overlayShown) ui.mouseReleased(x, y, button)
          else if (button != 1) client.mouseButtonReleased(button) // the left one targets and places zones
      }
    }
  }
}

final class GameClient(
    ws: WebSocket,
    user: String,
    headless: Boolean,
    val controls: Controls,
    val controlsInfo: String,
    mouseWorld: () => Complex,
    /** The entity whose frame (in the HUD) is under the mouse. */
    hovered: () => Option[Entity.Id],
    /** Simulated round trip latency in seconds (BFF_LAG_MS), half on each way, to try the prediction. */
    lag: Double = 0
) {
  private val incoming = scala.collection.mutable.Queue.empty[(Double, String)]
  private val outgoing = scala.collection.mutable.Queue.empty[(Double, String)]

  private var gatherer: ActionGatherer    = GreedyActionGatherer(GameState.empty)
  var myId: Option[Entity.Id]             = None
  var messagesReceived                    = 0
  var roundTrip                           = -1L
  private var elapsed                     = 0.0
  private var lastStatus                  = 0.0
  private var pingCount                   = 0
  private var actionsReceived             = 0
  private var bytesReceived               = 0L
  private var clockOffset                 = 0L
  private var gameBeganAt: Option[Double] = None
  private var scriptedMovements           = 0
  private var scriptedDirection           = Complex.zero


  /** Whether the player faces its target (instead of the mouse), toggled by `ToggleTargetLockIn`.
    */
  var lockInToTarget = false

  // Abilities and targeting
  private var caster: Option[AbilityCaster]       = None
  private var targetId: Option[Entity.Id]         = None
  var choosingPosition: Option[Ability.AbilityId] = None
  private var lastError: Option[(String, Double)] = None
  private var castsSent                           = 0
  private val recentTargets = scala.collection.mutable.Map.empty[Entity.Id, Double]
  /** Our last movement, shown before the server echoes it (the frontend's "unconfirmed actions"). */
  private var predictedMovement: Option[MovingBodyMoves] = None
  private var lastRotationSent: Option[Double]           = None
  private var lastMovementSentAt                         = 0L
  private var projectedState: GameState                  = GameState.empty
  private var lastScriptedCast                                    = 0.0

  /** The message of the last refused cast, for 2 seconds. */
  def errorMessage: Option[String] = lastError.collect {
    case (message, at) if elapsed - at < 2 => message
  }

  /** Living entities that can be targeted, bosses first. */
  def targetableEntities: List[LivingEntity & Body] =
    gameState.entities.values.toList
      .collect { case e: (LivingEntity & Body) => e }
      .sortBy(e => (if (gameState.bosses.contains(e.id)) 0 else 1, e.id.toString))

  /** The selected target if it still exists, otherwise the boss. */
  /** Targets this entity (e.g. its frame was clicked). */
  def selectTarget(id: Entity.Id): Unit = targetId = Some(id)

  def currentTarget: Option[Entity.Id] =
    targetId.filter(gameState.entities.contains).orElse(gameState.bosses.keys.headOption)

  private def request(castRequest: CastRequest): Unit = castRequest match {
    case CastRequest.Send(action) =>
      send(GameActionWrapper(Vector(action)))
      castsSent += 1
    case CastRequest.ChoosePosition(abilityId) =>
      choosingPosition = Some(abilityId)
    case CastRequest.Error(message) =>
      lastError = Some((message, elapsed))
      if (headless) println(s"Cast refused: $message")
  }

  /** Escape is not part of the controls: it cancels the zone placement, or opens the game menu (or
    * quits, when headless).
    */
  def keyPressed(scancode: String): Unit =
    if scancode == "escape" then {
      if choosingPosition.isDefined then choosingPosition = None
      else if headless then quit()
      else menuOpen = true
    }

  /** Like the frontend, inputs are triggered when keys are released. Movement is polled every
    * frame.
    */
  def keyReleased(scancode: String): Unit =
    LoveInput.inputCode(scancode).map(controls.getOrUnknown).foreach(handleInput)

  /** Mouse buttons can be bound in the controls too (`button` is LÖVE's number). */
  def mouseButtonReleased(button: Int): Unit = handleInput(controls.getOrUnknown(LoveInput.mouseCode(button)))

  def handleInput(input: UserInput): Unit = input match {
    case abilityInput: UserInput.AbilityInput =>
      choosingPosition = None
      for {
        c  <- caster
        id <- myId
        me <- gameState.players.get(id)
      } abilityInput.abilityId(me) match {
        case Some(abilityId) =>
          request(c.cast(abilityId, me, gameState, serverNow, currentTarget, mouseWorld(),
            if (controls.mouseOverEnabled) hovered() else None))
        case None =>
          request(CastRequest.Error(s"No ability on input ${abilityInput.abilityIndex + 1}"))
      }
    case UserInput.NextTarget =>
      nextTarget()
    case UserInput.ToggleTargetLockIn =>
      lockInToTarget = !lockInToTarget
    case UserInput.GameMarkerInput(marker, onTarget) =>
      val info =
        if (onTarget) currentTarget.map(GameMarkerInfo.GameMarkerOnEntity(marker, _))
        else Some(GameMarkerInfo.FixedGameMarker(marker, mouseWorld()))
      info.foreach(i =>
        send(GameActionWrapper(Vector(UpdateMarker(GameAction.Id.dummy, serverNow, i))))
      )
    case _ =>
      () // directions are polled every frame
  }

  /** Like the frontend's `NextTargetHandler`: the closest mob not chosen in the last 5 seconds. */
  private def nextTarget(): Unit =
    for {
      id <- myId
      me <- gameState.players.get(id)
    } {
      val candidates =
        gameState.allTargetableEntities.filter(_.teamId == Entity.teams.mobTeam).toList
      val ids = candidates.map(_.id).toSet
      recentTargets.filterInPlace((targetId, _) => ids.contains(targetId))
      val tooRecent = recentTargets.collect {
        case (targetId, at) if elapsed - at <= 5 => targetId
      }.toSet
      val next = candidates
        .filterNot(e => tooRecent.contains(e.id))
        .minByOption(_.pos.distanceTo(me.pos))
        .map(_.id)
        .orElse(recentTargets.minByOption(_._2).map(_._1))
      next.foreach { chosen =>
        recentTargets(chosen) = elapsed
        targetId = Some(chosen)
      }
    }

  /** Left click: places the zone being chosen, or selects the entity under the mouse. */
  def clicked(world: Complex): Unit = choosingPosition match {
    case Some(abilityId) =>
      choosingPosition = None
      caster.foreach(c => request(c.castAtPosition(abilityId, world, gameState, serverNow)))
    case None =>
      val now = serverNow
      targetableEntities
        .find(e => e.shape.contains(world, e.currentPosition(now), e.rotation))
        .foreach(e => targetId = Some(e.id))
  }

  send(Ready(user))

  /** The game state from the server, with our predicted movement: what we show and play with. */
  def gameState: GameState = projectedState

  /** The game state from the server only. */
  def serverGameState: GameState = gatherer.currentGameState

  private def project(): Unit =
    projectedState = gatherer.currentGameState.applyActions(predictedMovement.toVector)

  /** Smoothed positions of the entities moved by the server, for drawing. */
  val smoothing = new Smoothing

  /** Visual effects of the actions (damage numbers, ...). */
  val effects = new Effects

  // Headless: the largest jumps and turns of the boss between two frames, actual and smoothed
  private var previousBoss: Option[(Complex, Complex, Double, Double)] = None
  private var largestBossJumps                                         = (0.0, 0.0)
  private var largestBossTurns                                         = (0.0, 0.0)

  private def measureBossJumps(): Unit =
    for (boss <- gameState.bosses.values.headOption) {
      val now     = serverNow
      val current = (boss.currentPosition(now), smoothing.position(boss, now), boss.rotation.value,
        smoothing.rotation(boss).value)
      for ((raw, shown, rawRotation, shownRotation) <- previousBoss) {
        def turn(from: Double, to: Double) = math.abs(Smoothing.shortestAngle(to - from))
        largestBossJumps = (math.max(largestBossJumps._1, (current._1 - raw).modulus),
          math.max(largestBossJumps._2, (current._2 - shown).modulus))
        largestBossTurns = (math.max(largestBossTurns._1, turn(rawRotation, current._3)),
          math.max(largestBossTurns._2, turn(shownRotation, current._4)))
      }
      previousBoss = Some(current)
    }

  /** Estimate of the server's clock (from the ping/pong exchanges), used to interpolate positions.
    */
  def serverNow: Long = System.currentTimeMillis() + clockOffset

  private def send(message: InGameWSProtocol): Unit = {
    val buffer = Pickle.intoBytes[InGameWSProtocol](message)
    val bytes  = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    if (lag > 0) outgoing.enqueue((elapsed + lag / 2, ScalaLove.bytesToString(bytes)))
    else ws.send(ScalaLove.bytesToString(bytes))
  }

  def quit(): Unit = {
    ws.close()
    Event.quit(0)
  }

  /** The game ended, or the game server closed the connection. */
  def finished: Boolean = gameState.ended || !ws.isOpen()

  /** The game menu (opened with Escape). The game goes on, but the player stops moving. */
  var menuOpen = false

  /** The human players of the game (the AIs are not included). */
  private var humanPlayers = Vector.empty[String]

  /** How leaving the game now would end it. */
  def exit: GameExit =
    if (gameState.ended) GameExit.Ended
    else if (!ws.isOpen()) GameExit.ConnectionLost
    else GameExit.LeftEarly(onlyHuman = humanPlayers.forall(_ == user))

  /** Closes the connection to the game server (e.g. to go back to the menus). */
  def leave(): Unit = ws.close()

  def update(dt: Double): Unit = {
    elapsed += dt

    var data = ws.poll()
    while (data != null) {
      incoming.enqueue((elapsed + lag / 2, data))
      data = ws.poll()
    }
    while (incoming.nonEmpty && incoming.head._1 <= elapsed) {
      val (_, message) = incoming.dequeue()
      bytesReceived += message.length
      messagesReceived += 1
      handle(Unpickle[InGameWSProtocol].fromBytes(ByteBuffer.wrap(ScalaLove.stringToBytes(message))))
    }
    while (outgoing.nonEmpty && outgoing.head._1 <= elapsed) ws.send(outgoing.dequeue()._2)

    if (elapsed - lastStatus >= 1.0) {
      lastStatus = elapsed
      pingCount += 1
      send(Ping(System.currentTimeMillis(), s"ping-$pingCount"))
      if (headless) println(status)
    }

    project()
    if (gameBeganAt.isDefined && !finished) {
      if (headless) { scriptedMovement(dt); scriptedCasts() }
      else keyboardMovement(dt)
      project()
    }
    smoothing.update(gameState, serverNow, dt, myId)
    if (headless) measureBossJumps()

    if (headless && (gameState.ended || elapsed > 60 || !ws.isOpen())) {
      println(status)
      println(
        s"Finished: game ended=${gameState.ended}, players won=${gameState.playersWon}, " +
          s"socket open=${ws.isOpen()}, $messagesReceived messages ($bytesReceived bytes), $actionsReceived actions, " +
          s"$castsSent casts sent, largest boss jump between frames: " +
          f"${largestBossJumps._1}%.1f (smoothed: ${largestBossJumps._2}%.1f), " +
          f"largest boss turn between frames: ${largestBossTurns._1}%.2f rad (smoothed: ${largestBossTurns._2}%.2f)"
      )
      quit()
    }
  }

  /** Movement from the held direction controls, facing the mouse (or the target when locked in),
    * like the frontend.
    */
  private def keyboardMovement(dt: Double): Unit =
    for {
      id <- myId
      me <- gameState.players.get(id)
    } {
      val directionKeys = Map[UserInput, Controls.InputCode](
        UserInput.Up    -> controls.upKey,
        UserInput.Down  -> controls.downKey,
        UserInput.Left  -> controls.leftKey,
        UserInput.Right -> controls.rightKey
      )
      val held = directionKeys.collect {
        case (input, code) if !menuOpen && LoveInput.isDown(code) => input
      }.toSet
      val now      = serverNow
      val position = me.currentPosition(now)
      val facing = (for {
        targetId <- currentTarget if lockInToTarget
        case target: Body <- gameState.entities.get(targetId)
      } yield target.currentPosition(now) - position).getOrElse(mouseWorld() - position)
      move(UserInput.movingDirection(held), facing, dt)
    }

  /** Moves our player for this frame, like the frontend: the new position is computed here (with the
    * obstacles), sent to the server, and shown right away on top of the server's game state, without
    * waiting for the server to echo it (one round trip later).
    */
  private def move(direction: Complex, facing: Complex, dt: Double): Unit =
    for {
      id <- myId
      me <- gameState.players.get(id)
    } {
      val now       = serverNow
      val moving    = direction != Complex.zero
      val obstacles = gameState.obstacles.values
      val position =
        if (moving) {
          // At most 100 ms, so that a long frame does not make us jump
          val step   = me.speed * math.min(dt, 0.1) * Complex.rotation(direction.arg)
          val target = me.pos + step // our previous frame (predicted) position
          // BFF's lastValidPosition ignores moves under 2 units, which are common at high frame rates
          if (step.modulus >= 2) me.lastValidPosition(target, obstacles)
          else if (obstacles.exists(o => o.shape.collides(o.pos, o.rotation, me.shape, target, me.rotation))) me.pos
          else target
        } else me.currentPosition(now, obstacles)
      val action = MovingBodyMoves(GameAction.Id.zero, now, id, position,
        if (moving) direction.arg else me.direction, facing.arg, me.speed, moving)
      // Sent when starting, stopping, turning or changing direction, and about 60 times per second
      // while moving (the rotation is rounded, so that tiny mouse moves do not flood the server)
      val rotation         = math.round(facing.arg.value * 50) / 50.0
      val directionChanged = moving && me.direction != action.direction
      if (moving != me.moving || directionChanged || !lastRotationSent.contains(rotation) ||
          (moving && now - lastMovementSentAt >= 16)) {
        lastRotationSent = Some(rotation)
        lastMovementSentAt = now
        send(GameActionWrapper(Vector(action)))
      }
      predictedMovement = Some(action)
    }

  /** Two seconds after the start, walk towards the boss for one second. */
  private def scriptedMovement(dt: Double): Unit =
    for {
      began <- gameBeganAt
      boss  <- gameState.bosses.values.headOption
      id    <- myId
      me    <- gameState.players.get(id)
    } {
      val towardsBoss = boss.currentPosition(serverNow) - me.currentPosition(serverNow)
      val walking     = elapsed - began > 2 && elapsed - began <= 3
      if (walking && scriptedMovements == 0) {
        println(s"Walking towards the boss from ${me.currentPosition(serverNow)}")
        scriptedDirection = towardsBoss.safeNormalized
        scriptedMovements += 1
      } else if (!walking && scriptedMovements == 1) {
        val server = serverGameState.players.get(id).map(_.currentPosition(serverNow))
        println(s"Stopping at ${me.currentPosition(serverNow)} (server: ${server.getOrElse("-")})")
        scriptedMovements += 1
      }
      move(if (walking) scriptedDirection else Complex.zero, towardsBoss, dt)
    }

  /** Once at the boss, hit it (the triangle's Direct Hit) every half second. */
  private def scriptedCasts(): Unit =
    for {
      c  <- caster
      id <- myId
      me <- gameState.players.get(id)
    }
      if (
        scriptedMovements == 2 && elapsed - lastScriptedCast > 0.5 && !gameState.entityIsCasting(id)
      ) {
        lastScriptedCast = elapsed
        request(
          c.cast(Ability.triangleDirectHit, me, gameState, serverNow, currentTarget, Complex.zero)
        )
      }

  private def status: String = {
    val bosses =
      gameState.bosses.values.map(b => s"${b.name} life=${math.round(b.life)}").mkString(", ")
    def show(z: Complex) = s"(${math.round(z.re)}, ${math.round(z.im)})"
    val me = myId.flatMap(gameState.players.get).fold("-") { p =>
      val server = myId.flatMap(serverGameState.players.get).fold("-")(s => show(s.currentPosition(serverNow)))
      s"life=${math.round(p.life)} pos=${show(p.currentPosition(serverNow))} (server: $server)"
    }
    s"[t=${math.round(elapsed * 10) / 10.0}s] messages=$messagesReceived actions=$actionsReceived entities=${gameState.entities.size} " +
      s"boss: [$bosses] me: $me rtt=${roundTrip}ms started=${gameState.started} ended=${gameState.ended}"
  }

  private def handle(message: InGameWSProtocol): Unit = message match {
    case AllPlayers(names) =>
      println(s"<- AllPlayers(${names.mkString(", ")})")
      humanPlayers = names.toVector
    case BuddyIsReady(name) =>
      println(s"<- BuddyIsReady($name)")
      if (name == user) send(ReadyToStart(user))
    case EveryoneIsReady =>
      println("<- EveryoneIsReady, sending LetsBegin")
      send(LetsBegin)
      gameBeganAt = Some(elapsed)
    case YourEntityIdIs(id) =>
      println(s"<- YourEntityIdIs($id)")
      myId = Some(id)
      caster = Some(new AbilityCaster(id))
    case StartingBossPosition(x, y) =>
      println(s"<- StartingBossPosition($x, $y)")
    case AddAndRemoveActions(toAdd, oldestTimeToRemove, idsToRemove) =>
      actionsReceived += toAdd.length
      gatherer = gatherer.slaveAddAndRemoveActions(toAdd, oldestTimeToRemove, idsToRemove)
      effects.received(toAdd, gatherer.currentGameState, myId)
    case RemoveActions(oldestTime, ids) =>
      gatherer = gatherer.slaveAddAndRemoveActions(Vector.empty, oldestTime, ids)
    case Pong(originalSendingTime, midwayDistantTime, _) =>
      val now = System.currentTimeMillis()
      roundTrip = now - originalSendingTime
      clockOffset = midwayDistantTime - (originalSendingTime + now) / 2
    case HeartBeat =>
      ()
    case other =>
      println(s"<- unexpected $other")
  }
}
