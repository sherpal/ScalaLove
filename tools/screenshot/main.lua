-- Runs a generated game in LÖVE, takes screenshots at the given frames, then quits.
-- Usage: love tools/screenshot <game dir> [frame,frame,...] [key:from-to,key@frame,...]
--   e.g. love tools/screenshot game/target/lua 60,120 w:30-90,1@100
-- `key:from-to` reports the key as held between the given frames (love.keyboard.isDown and
-- isScancodeDown), `key@frame` sends a key press and release (love.keypressed then
-- love.keyreleased) at that frame. Keys are given as LÖVE scancodes.
-- Screenshots are written to LÖVE's save directory (scalalove-screenshot/screenshot-<frame>.png).
love.filesystem.setIdentity("scalalove-screenshot")
local gameDir = arg[2]
local frames = {}
for f in (arg[3] or "90"):gmatch("%d+") do frames[#frames + 1] = tonumber(f) end
local lastFrame = frames[#frames]
local heldKeys, pressedKeys = {}, {}
for key, from, to in (arg[4] or ""):gmatch("(%w+):(%d+)-(%d+)") do
  heldKeys[#heldKeys + 1] = { key = key, from = tonumber(from), to = tonumber(to) }
end
for key, at in (arg[4] or ""):gmatch("(%w+)@(%d+)") do
  pressedKeys[#pressedKeys + 1] = { key = key, at = tonumber(at) }
end

package.path = gameDir .. "/?.lua;" .. package.path
dofile(gameDir .. "/main.lua")

local frame = 0
local function simulate(original)
  return function(key, ...)
    for _, k in ipairs(heldKeys) do
      if k.key == key and frame >= k.from and frame <= k.to then return true end
    end
    return original(key, ...)
  end
end
love.keyboard.isDown = simulate(love.keyboard.isDown)
love.keyboard.isScancodeDown = simulate(love.keyboard.isScancodeDown)

local update = love.update
love.update = function(dt)
  update(dt)
  frame = frame + 1
  for _, k in ipairs(pressedKeys) do
    if k.at == frame then
      if love.keypressed then love.keypressed(k.key, k.key, false) end
      if love.keyreleased then love.keyreleased(k.key, k.key) end
    end
  end
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
