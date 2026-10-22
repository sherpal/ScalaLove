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
  end
end
