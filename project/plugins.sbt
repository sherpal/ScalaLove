addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")

// To package LÖVE games (project/LovePackage.scala)
libraryDependencies += "org.apache.commons" % "commons-compress" % "1.27.1"
