-- Runs a generated main.lua without LÖVE, with a fake `love` API that logs draw calls.
-- Usage: luajit tools/headless.lua <dir-with-main.lua> [frames]
local dir, frames = arg[1], tonumber(arg[2] or "0")
local log = {}
local function logger(name)
  return function(...)
    local parts = {}
    for i = 1, select("#", ...) do parts[i] = tostring((select(i, ...))) end
    log[#log + 1] = name .. "(" .. table.concat(parts, ", ") .. ")"
  end
end
local down = {}
love = {
  graphics = setmetatable({
    getWidth = function() return 800 end,
    getHeight = function() return 600 end,
    getDimensions = function() return 800, 600 end,
  }, { __index = function(_, k) return logger("graphics." .. k) end }),
  keyboard = { isDown = function(k) return down[k] == true end },
  event = { quit = logger("event.quit") },
}
dofile(dir .. "/main.lua")
if frames > 0 then
  down["w"] = true
  for _ = 1, frames do
    love.update(1 / 60)
    log = {}
    love.draw()
  end
  print(table.concat(log, "\n"))
end
