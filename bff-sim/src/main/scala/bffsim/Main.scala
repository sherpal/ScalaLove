package bffsim

import gamelogic.entities.boss.BossFactory

object Main {
  def main(args: Array[String]): Unit = {
    println("Bosses: " + BossFactory.factoriesByBossName.keys.toList.sorted.mkString(", "))
    val result = Simulation.run("Boss 101", playersPerClass = 2, maxTicks = 20000, println(_))
    println(s"Done after ${result.ticks} ticks, ${result.actionCount} actions, " +
      s"players won: ${result.gameState.playersWon}")
  }
}
