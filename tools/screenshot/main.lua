-- Runs a generated game in LÖVE, takes screenshots at the given frames, then quits.
-- Usage: love tools/screenshot <game dir> [frame,frame,...] [key:from-to,...]
--   e.g. love tools/screenshot game/target/lua 60,120 w:30-90
-- Keys listed in the third argument are reported as held between the given frames.
-- Screenshots are written to LÖVE's save directory (scalalove-screenshot/screenshot-<frame>.png).
love.filesystem.setIdentity("scalalove-screenshot")
local gameDir = arg[2]
local frames = {}
for f in (arg[3] or "90"):gmatch("%d+") do frames[#frames + 1] = tonumber(f) end
local lastFrame = frames[#frames]
local heldKeys = {}
for key, from, to in (arg[4] or ""):gmatch("(%w+):(%d+)-(%d+)") do
  heldKeys[#heldKeys + 1] = { key = key, from = tonumber(from), to = tonumber(to) }
end

package.path = gameDir .. "/?.lua;" .. package.path
dofile(gameDir .. "/main.lua")

local frame = 0
local isDown = love.keyboard.isDown
love.keyboard.isDown = function(key, ...)
  for _, k in ipairs(heldKeys) do
    if k.key == key and frame >= k.from and frame <= k.to then return true end
  end
  return isDown(key, ...)
end

local update = love.update
love.update = function(dt)
  update(dt)
  frame = frame + 1
  for _, f in ipairs(frames) do
    if frame == f then
      love.graphics.captureScreenshot(function(imageData)
        local name = "screenshot-" .. f .. ".png"
        imageData:encode("png", name)
        print("screenshot: " .. love.filesystem.getSaveDirectory() .. "/" .. name)
        if f == lastFrame then love.event.quit() end
      end)
    end
  end
end
