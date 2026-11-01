ThisBuild / scalaVersion := "3.8.4"

val scalaJSVersion = "1.22.0"

lazy val luaOutput = settingKey[File]("Directory where the Lua program is written")
lazy val luaTarget =
  settingKey[String]("Lua target: luajit (LÖVE) or lua51 (plain Lua 5.1, e.g. World of Warcraft)")
lazy val luaLink    = taskKey[File]("Link the program to Lua (main.lua)")
lazy val luaRun     = taskKey[Unit]("Link the game to Lua and run it with LÖVE")
lazy val luaTest    = taskKey[Unit]("Link the program to Lua and run it headlessly with LuaJIT")
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
    luaTarget := "luajit",
    luaLink := Def.taskDyn {
      val cp = (Compile / fullClasspath).value
        .map(_.data.getAbsolutePath)
        .mkString(java.io.File.pathSeparator)
      val out     = luaOutput.value
      val mainCls = (Compile / mainClass).value.getOrElse(sys.error("No main class"))
      val target  = luaTarget.value
      Def.task {
        (linker / Compile / runMain)
          .toTask(s" scalalove.linker.Main $mainCls ${out.getAbsolutePath} $cp $target")
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
    luaTest := {
      val dir  = luaLink.value
      val tool = (LocalRootProject / baseDirectory).value / "tools" / "headless.lua"
      val exit =
        scala.sys.process.Process(Seq("luajit", tool.getAbsolutePath, dir.getAbsolutePath)).!
      if (exit != 0) sys.error(s"luajit exited with code $exit")
    }
  )

def loveProject(p: Project) = luaProject(p).settings(
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
  )
)

lazy val wowToc       = settingKey[WOWPackage.Toc]("Table of contents info for WoW addon")
lazy val addonOutput  = settingKey[File]("Folder name where to output addon")
lazy val addonsFolder = settingKey[File]("Folder of your World of Warcraft installation")

lazy val packageAddon = taskKey[File]("Package the addon in the output dir.")
lazy val installAddon = taskKey[File]("Package and install the addon directly in the AddOns folder")

def wowProject(p: Project) = luaProject(p).settings(
  luaTarget := "lua51",
  addonOutput := target.value / "addon" / wowToc.value.name.filterNot(_ == ' '),
  addonsFolder := new File(
    "C:\\Program Files (x86)\\World of Warcraft\\_retail_\\Interface\\AddOns"
  ),
  packageAddon := Def.taskDyn {
    val toc      = wowToc.value
    val link     = luaLink.value
    val luaFiles = IO.listFiles(link)
    Def.task {
      val output = addonOutput.value
      streams.value.log.info(s"Packaging addon in $output")
      IO.delete(output)
      for {
        file <- luaFiles
      } IO.copyFile(file, output / file.name)
      IO.write(output / s"${output.getName}.toc", toc.mkFile(luaFiles.toSeq.map(_.name)))
      output
    }
  }.value,
  installAddon := Def.taskDyn {
    val packaged = packageAddon.value
    val folder   = addonsFolder.value

    Def.task {
      streams.value.log.info(s"Copying packaged addon from $packaged to $folder")
      val out = folder / packaged.name
      IO.copyDirectory(packaged, out)
      out
    }
  }.value
)

// The LÖVE facades and the Pong game
lazy val game = loveProject(project)

lazy val wowAddon = wowProject(project.in(file("wow-addon")))
  .settings(
    wowToc := WOWPackage.Toc(Seq("120105"), "From Scala", "1.0", "Me", "This was made in Scala!")
  )

// A headless program checking the semantics of the generated Lua code
lazy val selftest = luaProject(project)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-parser" % "0.14.9",
      "io.circe" %%% "circe-generic" % "0.14.9"
    )
  )
