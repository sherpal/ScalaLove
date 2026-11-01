package demoaddon

import wow.ui.Frame

import scala.scalajs.js

@main def run(): Unit = {
  println("Hello from scala!")
  println("I was re-installed again!")

  val moneyFrame =
    Frame.create("Frame", "MoneyFrame", Frame.UIParent, "BasicFrameTemplateWithInset")
  moneyFrame.SetSize(500, 400)
  moneyFrame.SetPoint("CENTER", Frame.UIParent, "CENTER")
  moneyFrame.RegisterEvent("PLAYER_MONEY")
  moneyFrame.SetScript("OnEvent", () => println(s"You have ${wow.api.GetMoney()} copper"))

  val titleBg = moneyFrame
    .asInstanceOf[js.Dynamic]
    .selectDynamic("TitleBg")
    .asInstanceOf[Frame]

  titleBg.SetHeight(30)

  val title = moneyFrame.CreateFontString(js.undefined, "OVERLAY", "GameFontHighlight")
  title.SetPoint("TOPLEFT", titleBg, "TOPLEFT", 5, -3)
  title.SetText("Hello from Scala")

  val body = Frame.create("Frame", parent = moneyFrame)
  body.SetAllPoints()
  val greeting = body.CreateFontString(js.undefined, "OVERLAY", "GameFontHighlight")
  greeting.SetPoint("TOPLEFT", titleBg, "BOTTOMLEFT", 5, -50)
  greeting.SetText(s"Welcome, ${wow.api.UnitName("player")}!")

}
