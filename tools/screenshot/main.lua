-- Runs a generated game in LÖVE, takes a screenshot after N frames, then quits.
-- Usage: love tools/screenshot <game dir> [frames]
-- The screenshot is written to LÖVE's save directory (scalalove-screenshot/screenshot.png).
love.filesystem.setIdentity("scalalove-screenshot")
local gameDir = arg[2]
local target = tonumber(arg[3] or "90")
dofile(gameDir .. "/main.lua")
local update = love.update
local frames = 0
love.update = function(dt)
  update(dt)
  frames = frames + 1
  if frames == target then
    love.graphics.captureScreenshot(function(imageData)
      imageData:encode("png", "screenshot.png")
      print("screenshot: " .. love.filesystem.getSaveDirectory() .. "/screenshot.png")
      love.event.quit()
    end)
  end
end
