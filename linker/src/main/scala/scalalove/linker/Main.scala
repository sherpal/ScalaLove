package scalalove.linker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import ExecutionContext.Implicits.global

import org.scalajs.ir.Names.*
import org.scalajs.ir.Types.*
import org.scalajs.ir.WellKnownNames.*
import org.scalajs.linker.*
import org.scalajs.linker.interface.*
import org.scalajs.linker.standard.*
import org.scalajs.logging.*

object LuaBackend {
  val JavaScriptExceptionClass: ClassName = ClassName("scala.scalajs.js.JavaScriptException")
  val AnyArgConstructorName: MethodName = MethodName.constructor(List(ClassRef(ObjectClass)))
  val StringArgConstructorName: MethodName = MethodName.constructor(List(ClassRef(BoxedStringClass)))
  val toStringMethodName: MethodName = MethodName("toString", Nil, ClassRef(BoxedStringClass))
  val hashCodeMethodName: MethodName = MethodName("hashCode", Nil, IntRef)

  /** Symbols the generated code (and runtime) uses directly. */
  val symbolRequirements: SymbolRequirement = {
    val factory = SymbolRequirement.factory("lua-backend")
    import factory.*
    multiple(
      instantiateClass(JavaScriptExceptionClass, AnyArgConstructorName),
      instantiateClass(ArithmeticExceptionClass, StringArgConstructorName),
      callMethod(BoxedDoubleClass, hashCodeMethodName),
      callMethod(BoxedStringClass, hashCodeMethodName),
      classData(ClassClass)
    )
  }

  /** Semantics: no checks (like Scala.js' fullLink), they would need extra runtime support. */
  val semantics: Semantics = Semantics.Defaults
    .withAsInstanceOfs(CheckedBehavior.Unchecked)
    .withArrayIndexOutOfBounds(CheckedBehavior.Unchecked)
    .withArrayStores(CheckedBehavior.Unchecked)
    .withNegativeArraySizes(CheckedBehavior.Unchecked)
    .withNullPointers(CheckedBehavior.Unchecked)
    .withStringIndexOutOfBounds(CheckedBehavior.Unchecked)
    .withModuleInit(CheckedBehavior.Unchecked)

  def runtime: String = {
    val in = getClass.getResourceAsStream("/scalalove/runtime.lua")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
  }
}

/** Usage: Main <main class> <output dir> <classpath> */
object Main {
  def main(args: Array[String]): Unit = {
    val Array(mainClass, outDir, cp) = args
    val classpath = cp.split(java.io.File.pathSeparator).toSeq.filter(_.nonEmpty).map(Paths.get(_))

    val config = StandardConfig()
      .withSemantics(LuaBackend.semantics)
      .withOptimizer(false)
      .withCheckIR(true)
    val frontend = StandardLinkerFrontend(config)
    val logger = new ScalaConsoleLogger(Level.Warn)

    val moduleSetFuture = for {
      (containers, _) <- PathIRContainer.fromClasspath(classpath)
      irFiles <- StandardImpl.irFileCache().newCache.cached(containers)
      moduleSet <- frontend.link(irFiles,
          Seq(ModuleInitializer.mainMethodWithArgs(mainClass, "main")),
          LuaBackend.symbolRequirements, logger)
    } yield moduleSet
    val moduleSet = Await.result(moduleSetFuture, Duration.Inf)

    val lua = new LuaEmitter(moduleSet).emit(LuaBackend.runtime, msg => System.err.println(msg))

    val outPath = Paths.get(outDir)
    Files.createDirectories(outPath)
    val mainLua = outPath.resolve("main.lua")
    Files.write(mainLua, lua.getBytes(StandardCharsets.UTF_8))
    val classCount = moduleSet.modules.map(_.classDefs.size).sum
    println(s"Wrote $mainLua ($classCount classes, ${lua.linesIterator.size} lines)")
  }
}
