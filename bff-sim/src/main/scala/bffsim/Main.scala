package bffsim

import scala.collection.mutable

import gamelogic.entities.Entity
import gamelogic.entities.boss.BossFactory
import models.bff.ingame.InGameWSProtocol
import models.bff.ingame.InGameWSProtocol.*

object Main {
  def main(args: Array[String]): Unit = {
    println("Bosses: " + BossFactory.factoriesByBossName.keys.toList.sorted.mkString(", "))

    // The fight, recording the websocket traffic
    val traffic = mutable.ArrayBuffer[InGameWSProtocol](
      AllPlayers(Vector("alice", "bob")),
      Ready("alice"),
      BuddyIsReady("bob"),
      ReadyToStart("alice"),
      EveryoneIsReady,
      LetsBegin,
      HeartBeat,
      Ping(1234567890123L, "ping-1"),
      Pong(1234567890123L, 1234567890456L, "ping-1"),
      YourEntityIdIs(Entity.Id.fromLong(42L)),
      StartingBossPosition(12.5, -100.25),
      RemoveActions(1000123L, Vector.empty)
    )
    val result =
      Simulation.run("Boss 101", playersPerClass = 2, maxTicks = 20000, println(_), traffic += _)
    println(s"Done after ${result.ticks} ticks, ${result.actionCount} actions, " +
      s"players won: ${result.gameState.playersWon}")

    // Boopickle round-trip of all the traffic
    println("Small messages: " + traffic.take(12).map(m => PicklingCheck.hex(PicklingCheck.encode(m))).mkString(" "))
    val start  = System.nanoTime()
    val report = PicklingCheck.check(traffic, println(_))
    val millis = (System.nanoTime() - start) / 1000000
    println(s"Pickling: ${report.messages} messages, ${report.bytes} bytes, " +
      s"checksum ${report.checksum}, ${report.failures} failures")
    println(s"timing: round-trip of all messages in $millis ms (cold)")
    val warmStart = System.nanoTime()
    for (_ <- 1 to 10) PicklingCheck.check(traffic, _ => ())
    println(s"timing: round-trip of all messages in ${(System.nanoTime() - warmStart) / 10000000} ms (warm, average of 10)")
  }
}
