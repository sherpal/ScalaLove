function love.conf(t)
  t.identity = "scalalove-bff"
  if os.getenv("BFF_HEADLESS") then
    -- Headless: no window, no graphics
    t.window = false
    t.modules.window = false
    t.modules.graphics = false
    t.modules.audio = false
    t.modules.sound = false
  else
    t.window.title = "Battle for Flatland - ScalaLove client"
    t.window.width = 1200
    t.window.height = 800
    -- The game is drawn at any size (the HUD and menus keep theirs), and F11 makes it fullscreen
    t.window.resizable = true
    -- Narrower, the action bar would cover the threat meter (the HUD is not scaled yet)
    t.window.minwidth = 1200
    t.window.minheight = 700
  end
end
