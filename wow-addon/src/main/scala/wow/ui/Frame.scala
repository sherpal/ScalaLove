package wow.ui

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobalScope, JSName}

@js.native
trait Frame extends js.Object {

  def SetScript(event: String, handler: js.Function): Unit = js.native

  def RegisterEvent(event: String): Unit = js.native

  def SetSize(width: Int, height: Int): Unit = js.native
  
  def SetAllPoints(): Unit = js.native

  def SetHeight(height: Int): Unit = js.native

  def SetPoint(
      point: String,
      relativeTo: Frame,
      relativePoint: String,
      xOffset: Int = 0,
      yOffset: Int = 0
  ): Unit = js.native

  def CreateFontString(
      x: js.UndefOr[Nothing] = js.undefined,
      layer: String,
      font: String
  ): FontFrame = js.native

}

@js.native
@JSGlobalScope
object Frame extends js.Object {

  val UIParent: Frame = js.native

  @JSName("CreateFrame")
  def create(
      tpe: String,
      name: js.UndefOr[String] = js.undefined,
      parent: js.UndefOr[Frame] = js.undefined,
      template: js.UndefOr[String] = js.undefined
  ): Frame = js.native

}
