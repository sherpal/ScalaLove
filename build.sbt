ThisBuild / scalaVersion := "3.8.4"

val scalaJSVersion = "1.22.0"

lazy val luaOutput = settingKey[File]("Directory where the Lua program is written")
lazy val luaLink = taskKey[File]("Link the program to Lua (main.lua)")
lazy val luaRun = taskKey[Unit]("Link the game to Lua and run it with LÖVE")
lazy val luaTest = taskKey[Unit]("Link the program to Lua and run it headlessly with LuaJIT")
lazy val luaProbe = taskKey[Unit]("Print statistics about the linked IR (set DUMP_CLASS=a.B,c.D to dump classes)")

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
      val cp = (Compile / fullClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val out = luaOutput.value
      val mainCls = (Compile / mainClass).value.getOrElse(sys.error("No main class"))
      Def.task {
        (linker / Compile / runMain).toTask(s" scalalove.linker.Main $mainCls ${out.getAbsolutePath} $cp").value
        out
      }
    }.value,
    luaProbe := Def.taskDyn {
      val cp = (Compile / fullClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val mainCls = (Compile / mainClass).value.getOrElse(sys.error("No main class"))
      (linker / Compile / runMain).toTask(s" scalalove.linker.Probe $mainCls $cp")
    }.value,
    luaRun := {
      val dir = luaLink.value
      scala.sys.process.Process(Seq("love", dir.getAbsolutePath)).!
    },
    luaTest := {
      val dir = luaLink.value
      val tool = (LocalRootProject / baseDirectory).value / "tools" / "headless.lua"
      val exit = scala.sys.process.Process(Seq("luajit", tool.getAbsolutePath, dir.getAbsolutePath)).!
      if (exit != 0) sys.error(s"luajit exited with code $exit")
    }
  )

// The LÖVE facades and the Pong game
lazy val game = luaProject(project)

// A headless program checking the semantics of the generated Lua code
lazy val selftest = luaProject(project)
