ThisBuild / scalaVersion := "3.8.4"

val scalaJSVersion = "1.22.0"

lazy val luaOutput = settingKey[File]("Directory where the Lua program is written")
lazy val luaLink   = taskKey[File]("Link the program to Lua (main.lua)")
lazy val luaRun    = taskKey[Unit]("Link the game to Lua and run it with LÖVE")
lazy val luaTest   = taskKey[Unit]("Link the program to Lua and run it headlessly with LuaJIT")
lazy val luaPackage =
  taskKey[Seq[File]]("Package the game with LÖVE for Windows, macOS and Linux (in target/dist)")
lazy val luaPackageTitle =
  settingKey[String]("The packaged game's name (in camel case, the packages' file names)")
lazy val luaProbe =
  taskKey[Unit]("Print statistics about the linked IR (set DUMP_CLASS=a.B,c.D to dump classes)")

// The Scala.js IR -> Lua linker. Plain JVM project.
lazy val linker = project
  .settings(
    libraryDependencies += "org.scala-js" %% "scalajs-linker" % scalaJSVersion
  )

/** Settings for a Scala project compiled to Lua. */
def luaProject(p: Project): Project = p
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    luaOutput := target.value / "lua",
    luaLink := Def.taskDyn {
      val cp = (Compile / fullClasspath).value
        .map(_.data.getAbsolutePath)
        .mkString(java.io.File.pathSeparator)
      val out     = luaOutput.value
      val mainCls = (Compile / mainClass).value.getOrElse(sys.error("No main class"))
      Def.task {
        (linker / Compile / runMain)
          .toTask(s" scalalove.linker.Main $mainCls ${out.getAbsolutePath} $cp")
          .value
        // Lua modules of the project (src/main/lua) are copied next to main.lua
        val luaSources = (Compile / sourceDirectory).value / "lua"
        if (luaSources.exists()) IO.copyDirectory(luaSources, out, overwrite = true)
        out
      }
    }.value,
    luaProbe := Def.taskDyn {
      val cp = (Compile / fullClasspath).value
        .map(_.data.getAbsolutePath)
        .mkString(java.io.File.pathSeparator)
      val mainCls = (Compile / mainClass).value.getOrElse(sys.error("No main class"))
      (linker / Compile / runMain).toTask(s" scalalove.linker.Probe $mainCls $cp")
    }.value,
    luaRun := {
      val dir = luaLink.value
      scala.sys.process.Process(Seq("love", dir.getAbsolutePath)).!
    },
    luaPackageTitle := name.value,
    luaPackage := LovePackage(
      luaLink.value,
      luaPackageTitle.value,
      (LocalRootProject / target).value / s"love-${LovePackage.loveVersion}",
      target.value / "dist",
      streams.value.log
    ),
    luaTest := {
      val dir  = luaLink.value
      val tool = (LocalRootProject / baseDirectory).value / "tools" / "headless.lua"
      val exit =
        scala.sys.process.Process(Seq("luajit", tool.getAbsolutePath, dir.getAbsolutePath)).!
      if (exit != 0) sys.error(s"luajit exited with code $exit")
    }
  )

// The LÖVE facades and the Pong game
lazy val game = luaProject(project)

// A headless program checking the semantics of the generated Lua code
lazy val selftest = luaProject(project)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-parser" % "0.14.9",
      "io.circe" %%% "circe-generic" % "0.14.9"
    )
  )

// Experiment: BattleForFlatland's game logic (published locally from that repo with
// `sharedJS/publishLocal sharedJVM/publishLocal`, organization local.bff), run on the JVM and in Lua.
val bffVersion = "0.1.0-local"
def bffSimSettings = Seq(
  scalaVersion := "3.9.0",
  Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "bff-sim" / "src" / "main" / "scala"
)

lazy val bffSim = luaProject(project.in(file("bff-sim/lua")))
  .settings(bffSimSettings)
  .settings(libraryDependencies += "local.bff" %%% "shared" % bffVersion)

lazy val bffSimJVM = project
  .in(file("bff-sim/jvm"))
  .settings(bffSimSettings)
  .settings(
    libraryDependencies += "local.bff" %% "shared" % bffVersion,
    run / fork := true
  )

lazy val bffDir =
  settingKey[File]("The Battle for Flatland checkout (BFF_DIR), for the frontend's images")

// The frontend's images used by the client, in frontend/public/assets/in-game/gui
val bffImages = Seq(
  "players/triangle.png",
  "players/square.png",
  "players/pentagon.png",
  "players/hexagon.png",
  "abilities/player-casting-ability-animation.png",
  "abilities/cleave-animation.png",
  "abilities/sword.png",
  "misc/small-lock.png"
) ++ Seq( // the icons of the abilities
  "hexagon-hot",
  "hexagon-flash-heal",
  "create-hexagon-zone",
  "square-hammer-hit",
  "square-taunt",
  "square-enrage",
  "square-cleave",
  "triangle-energy-kick",
  "triangle-direct-hit",
  "triangle-upgrade-direct-hit",
  "triangle-stun",
  "triangle-cut",
  "pentagon-bullet",
  "create-pentagon-zone",
  "pentagon-dispel"
).map(icon => s"abilities/$icon.png") ++ Seq( // boss specific
  "boss/dawn-of-time/boss102/damage-zone-animation.png",
  "boss/dawn-of-time/boss102/living-damage-zone-animation.png"
) ++ Seq( // the icons of the buffs (those which are not icons of abilities)
  "abilities/boss101-big-dot.png",
  "boss/dawn-of-time/boss102/living-damage-zone.png",
  "boss/dawn-of-time/boss104/twin-debuff.png",
  "boss/dawn-of-time/boss105/volatile-charge.png",
  "boss/dawn-of-time/boss105/twin-bond.png",
  "boss/dawn-of-time/boss105/mourning.png",
  "boss/dawn-of-time/boss105/yearning.png",
  "boss/first-raid/sir-of-vice/punished.png",
  "boss/first-raid/sir-of-vice/purified.png",
  "boss/first-raid/sir-of-vice/inflamed.png"
)

// Experiment: a LÖVE client for Battle for Flatland (menus and game)
lazy val bffClient = luaProject(project.in(file("bff-client")))
  .settings(
    scalaVersion := "3.9.0",
    libraryDependencies += "local.bff" %%% "shared" % bffVersion,
    luaPackageTitle := "Battle for Flatland",
    bffDir := sys.env
      .get("BFF_DIR")
      .map(file)
      .getOrElse((ThisBuild / baseDirectory).value.getParentFile / "BattleForFlatland"),
    // The images are copied from the BFF checkout (like its code, they are not copied in this repo)
    luaLink := {
      val logger = streams.value.log
      val out    = luaLink.value
      val gui    = bffDir.value / "frontend" / "public" / "assets" / "in-game" / "gui"
      if (gui.exists()) for (image <- bffImages) IO.copyFile(gui / image, out / "assets" / image)
      else logger.warn(s"$gui not found: the client draws shapes instead of BFF's images")
      out
    }
  )
