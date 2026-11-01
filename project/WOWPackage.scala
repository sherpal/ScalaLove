object WOWPackage {

  case class Toc(
      interfaces: Seq[String],
      name: String,
      version: String,
      author: String,
      notes: String
  ) {
    def mkFile(luaFiles: Seq[String]): String = Seq(
      "Interface" -> interfaces.mkString(", "),
      "Title"     -> name,
      "Author"    -> author,
      "Notes"     -> notes,
      "Version"   -> version
    ).map { case (name, value) => s"## $name: $value" }.mkString("\n") ++ "\n" ++ luaFiles.mkString(
      "\n"
    ) ++ "\n"
  }

}
