-- Prelude of the LuaJIT target (LÖVE): bit operations and Longs from LuaJIT's libraries.

-- JIT tuning for large generated programs. With the defaults (512 KB of machine
-- code, 1000 traces), LuaJIT fills its machine-code area, flushes every trace and
-- recompiles them over and over, spending most of its time in the JIT compiler.
if jit and jit.opt then
  jit.opt.start("maxmcode=65536", "maxtrace=10000", "loopunroll=60")
end

local bit = require("bit")
local ffi = require("ffi")
local tobit, band, bor, bxor, bnot = bit.tobit, bit.band, bit.bor, bit.bxor, bit.bnot
local lshift, rshift, arshift = bit.lshift, bit.rshift, bit.arshift
R.ffi = ffi

-- ---------------------------------------------------------------------------
-- Console, clocks

local function printErr(...) io.stderr:write(table.concat({ ... }, " "), "\n") end
local traceback = debug.traceback

-- Wall clock: LuaSocket's (bundled with LÖVE) when available
local hasSocket, luasocket = pcall(require, "socket")
local wallClock = hasSocket and luasocket.gettime or os.time
local monoClock = wallClock

-- ---------------------------------------------------------------------------
-- Long arithmetic (int64_t cdata)

local int64 = ffi.typeof("int64_t")
local uint64 = ffi.typeof("uint64_t")
R.int64 = int64

-- Longs are not tables: no value has this metatable
local LongMT = {}
local LZERO = 0LL

local LMIN = -9223372036854775807LL - 1LL
local LMAX = 9223372036854775807LL

function R.isLong(x) return type(x) == "cdata" and ffi.istype(int64, x) end

function R.ldiv(a, b)
  if b == 0LL then R.arithmeticException("/ by zero") end
  if a == LMIN and b == -1LL then return LMIN end
  return a / b
end

function R.lrem(a, b)
  if b == 0LL then R.arithmeticException("/ by zero") end
  if b == -1LL then return 0LL end
  return a % b
end

function R.ludiv(a, b)
  if b == 0LL then R.arithmeticException("/ by zero") end
  return int64(ffi.cast(uint64, a) / ffi.cast(uint64, b))
end

function R.lurem(a, b)
  if b == 0LL then R.arithmeticException("/ by zero") end
  return int64(ffi.cast(uint64, a) % ffi.cast(uint64, b))
end

function R.lucmp(a, b)
  local ua, ub = ffi.cast(uint64, a), ffi.cast(uint64, b)
  if ua < ub then return -1 elseif ua > ub then return 1 else return 0 end
end

function R.d2l(x)
  if x ~= x then return 0LL end
  if x >= 9223372036854775807 then return LMAX end
  if x <= -9223372036854775808 then return LMIN end
  return int64(x)
end

function R.l2i(x)
  return tobit(tonumber(band(x, 0xffffffffLL)))
end

R.l2d = tonumber

function R.lclz(x)
  if x == 0LL then return 64 end
  local hi = tobit(tonumber(rshift(x, 32)))
  if hi ~= 0 then return R.clz(hi) end
  return 32 + R.clz(R.l2i(x))
end

function R.ltos(x)
  local s = tostring(x)
  return (s:gsub("LL$", ""))
end

-- ---------------------------------------------------------------------------
-- Floating point bits

local fbox = ffi.new("float[1]")
function R.fround(x) fbox[0] = x; return tonumber(fbox[0]) end

local bits = ffi.new("union { double d; int64_t l; float f; int32_t i; }")
function R.doubleToBits(x) bits.d = x; return bits.l end
function R.floatToBits(x) bits.f = x; return tonumber(bits.i) end

-- NaNs are made canonical: LuaJIT tags its values with NaN payloads (-1LL would read as nil)
function R.doubleFromBits(l)
  if band(l, 0x7ff0000000000000LL) == 0x7ff0000000000000LL and band(l, 0xfffffffffffffLL) ~= 0LL then
    return 0 / 0
  end
  bits.l = l
  return bits.d
end
function R.floatFromBits(i)
  if band(i, 0x7f800000) == 0x7f800000 and band(i, 0x7fffff) ~= 0 then return 0 / 0 end
  bits.i = i
  return tonumber(bits.f)
end
