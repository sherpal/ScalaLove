-- Prelude of the Lua 5.1 target (e.g. World of Warcraft): no `require`, FFI or `io`. Bit operations
-- use the `bit` library when there is one (World of Warcraft has it), pure Lua otherwise.
-- Longs are { hi, lo } tables.

local abs, frexp, ldexp = math.abs, math.frexp, math.ldexp

-- ---------------------------------------------------------------------------
-- 32-bit integers (signed, stored in doubles)

-- Like LuaJIT's: rounds to the nearest integer (adding 2^52 + 2^51 drops the fraction), then wraps
local function tobit(x)
  x = (x + 6755399441055744 - 6755399441055744) % 4294967296
  if x >= 2147483648 then return x - 4294967296 end
  return x
end

local band, bor, bxor
local nativeBit = bit
if type(nativeBit) == "table" and nativeBit.band then
  -- The library gets unsigned operands and its results are made signed, whatever its own convention
  local nband, nbor, nbxor = nativeBit.band, nativeBit.bor, nativeBit.bxor
  band = function(a, b) return tobit(nband(a % 4294967296, b % 4294967296)) end
  bor = function(a, b) return tobit(nbor(a % 4294967296, b % 4294967296)) end
  bxor = function(a, b) return tobit(nbxor(a % 4294967296, b % 4294967296)) end
else
  -- Bit by bit: slow, but only for a plain Lua 5.1 (tests)
  band = function(a, b)
    a, b = a % 4294967296, b % 4294967296
    local r, p = 0, 1
    while a > 0 and b > 0 do
      local x, y = a % 2, b % 2
      if x + y == 2 then r = r + p end
      a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return tobit(r)
  end
  bor = function(a, b)
    a, b = a % 4294967296, b % 4294967296
    local r, p = 0, 1
    while a > 0 or b > 0 do
      local x, y = a % 2, b % 2
      if x + y > 0 then r = r + p end
      a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return tobit(r)
  end
  bxor = function(a, b)
    a, b = a % 4294967296, b % 4294967296
    local r, p = 0, 1
    while a > 0 or b > 0 do
      local x, y = a % 2, b % 2
      if x + y == 1 then r = r + p end
      a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return tobit(r)
  end
end

-- Shifts are exact in doubles (multiplying or dividing by a power of 2); counts are taken mod 32
local function bnot(x) return -1 - tobit(x) end
local function lshift(x, n) return tobit((x % 4294967296) * 2 ^ (n % 32)) end
local function rshift(x, n) return tobit(floor((x % 4294967296) / 2 ^ (n % 32))) end
local function arshift(x, n) return floor(tobit(x) / 2 ^ (n % 32)) end

local bit = {
  tobit = tobit, band = band, bor = bor, bxor = bxor, bnot = bnot,
  lshift = lshift, rshift = rshift, arshift = arshift,
}

local function u32(x) return x % 4294967296 end

-- Not a literal: Lua 5.1 merges the constants -0.0 and 0 when a function has both
R.NEGZERO = -1 / huge

-- ---------------------------------------------------------------------------
-- Console, clocks

local stderr = io and io.stderr
local function printErr(...)
  if stderr then stderr:write(table.concat({ ... }, " "), "\n") else print(...) end
end

local traceback
if debug and debug.traceback then
  traceback = debug.traceback
else
  -- World of Warcraft has debugstack instead of the debug library
  traceback = function(msg, level)
    if msg ~= nil and type(msg) ~= "string" then return msg end
    local stack = debugstack and debugstack((level or 1) + 1) or ""
    if msg == nil then return stack end
    return msg .. "\n" .. stack
  end
end

local wallClock = os and os.time or time
-- World of Warcraft's GetTime has a millisecond precision
local monoClock = GetTime or (os and os.clock) or wallClock

-- ---------------------------------------------------------------------------
-- Longs: { hi, lo }, the two signed 32-bit halves of the two's complement. The arithmetic works on
-- unsigned halves (doubles in [0, 2^32)), whose sums and 16-bit partial products are exact.

local TWO32 = 4294967296
local POW2 = {}
for i = 0, 32 do POW2[i] = 2 ^ i end

local LongMT = { __name = "java.lang.Long" }
local function mkLong(hi, lo) return setmetatable({ hi = hi, lo = lo }, LongMT) end
R.long = mkLong

-- From unsigned (or any integral) halves
local function fromU(hi, lo) return mkLong(tobit(hi), tobit(lo)) end

local LZERO = mkLong(0, 0)
local LMIN, LMAX = mkLong(-2147483648, 0), mkLong(2147483647, -1)
R.LZERO = LZERO

function R.isLong(x) return type(x) == "table" and getmetatable(x) == LongMT end

-- From an integral double (|x| < 2^63)
function R.int64(x)
  local hi = floor(x / TWO32)
  return fromU(hi, x - hi * TWO32)
end

function R.d2l(x)
  if x ~= x then return LZERO end
  if x >= 9223372036854775807 then return LMAX end
  if x <= -9223372036854775808 then return LMIN end
  if x >= 0 then x = floor(x) else x = ceil(x) end
  return R.int64(x)
end

function R.l2i(x) return x.lo end
function R.l2d(x) return x.hi * TWO32 + u32(x.lo) end

function R.leq(a, b) return a.hi == b.hi and a.lo == b.lo end
function R.lne(a, b) return a.hi ~= b.hi or a.lo ~= b.lo end
function R.llt(a, b) return a.hi < b.hi or (a.hi == b.hi and u32(a.lo) < u32(b.lo)) end
function R.lle(a, b) return a.hi < b.hi or (a.hi == b.hi and u32(a.lo) <= u32(b.lo)) end
function R.lgt(a, b) return R.llt(b, a) end
function R.lge(a, b) return R.lle(b, a) end

function R.lucmp(a, b)
  local ah, bh = u32(a.hi), u32(b.hi)
  if ah ~= bh then return ah < bh and -1 or 1 end
  local al, bl = u32(a.lo), u32(b.lo)
  if al ~= bl then return al < bl and -1 or 1 end
  return 0
end

function R.lclz(x)
  if x.hi ~= 0 then return R.clz(x.hi) end
  return 32 + R.clz(x.lo)
end

function R.ladd(a, b)
  local lo = u32(a.lo) + u32(b.lo)
  local carry = lo >= TWO32 and 1 or 0
  return mkLong(tobit(a.hi + b.hi + carry), tobit(lo))
end

function R.lsub(a, b)
  local lo = u32(a.lo) - u32(b.lo)
  local borrow = lo < 0 and 1 or 0
  return mkLong(tobit(a.hi - b.hi - borrow), tobit(lo))
end

-- Low 32 bits of a product of 32-bit integers
local function mul32(a, b)
  local ua, ub = u32(a), u32(b)
  local ah = floor(ua / 65536)
  return tobit((ua - ah * 65536) * ub + ((ah * ub) % 65536) * 65536)
end

function R.lmul(a, b)
  -- The low halves' full product, by 16-bit parts; the high halves only matter mod 2^32
  local al, bl = u32(a.lo), u32(b.lo)
  local al1, bl1 = floor(al / 65536), floor(bl / 65536)
  local al0, bl0 = al - al1 * 65536, bl - bl1 * 65536
  local p00 = al0 * bl0
  local mid = al0 * bl1 + al1 * bl0 + floor(p00 / 65536)
  local lo = (mid % 65536) * 65536 + p00 % 65536
  local hi = al1 * bl1 + floor(mid / 65536) + mul32(a.hi, b.lo) + mul32(a.lo, b.hi)
  return mkLong(tobit(hi), tobit(lo))
end

-- Two's complement negation of signed halves
local function negHalves(hi, lo)
  if lo == 0 then return tobit(-hi), 0 end
  return tobit(-hi - 1), tobit(-lo)
end

-- Unsigned halves of |x| (2^63 for Long.MinValue), and whether x < 0
local function absHalves(x)
  if x.hi < 0 then
    local hi, lo = negHalves(x.hi, x.lo)
    return u32(hi), u32(lo), true
  end
  return x.hi, u32(x.lo), false
end

-- Unsigned division (b ~= 0), on unsigned halves: returns the quotient's and remainder's
local function udivmod(ah, al, bh, bl)
  if ah < 1048576 and bh < 1048576 then
    -- Both below 2^52: in doubles, correcting the rounded quotient
    local x, y = ah * TWO32 + al, bh * TWO32 + bl
    local q = floor(x / y)
    local r = x - q * y
    if r < 0 then q, r = q - 1, r + y elseif r >= y then q, r = q + 1, r - y end
    local qh, rh = floor(q / TWO32), floor(r / TWO32)
    return qh, q - qh * TWO32, rh, r - rh * TWO32
  end
  -- Bit by bit. Shifted, the remainder can need 65 bits (`over`), and is then above b.
  local qh, ql, rh, rl = 0, 0, 0, 0
  for i = 63, 0, -1 do
    local abit
    if i >= 32 then abit = floor(ah / POW2[i - 32]) % 2 else abit = floor(al / POW2[i]) % 2 end
    local over = rh >= 2147483648
    rh = (rh % 2147483648) * 2 + (rl >= 2147483648 and 1 or 0)
    rl = (rl % 2147483648) * 2 + abit
    if over or rh > bh or (rh == bh and rl >= bl) then
      rl = rl - bl
      if rl < 0 then rl, rh = rl + TWO32, rh - 1 end
      rh = rh - bh
      if rh < 0 then rh = rh + TWO32 end
      if i >= 32 then qh = qh + POW2[i - 32] else ql = ql + POW2[i] end
    end
  end
  return qh, ql, rh, rl
end

local function checkDivisor(b)
  if b.hi == 0 and b.lo == 0 then R.arithmeticException("/ by zero") end
end

function R.ldiv(a, b)
  checkDivisor(b)
  local ah, al, aNeg = absHalves(a)
  local bh, bl, bNeg = absHalves(b)
  local qh, ql = udivmod(ah, al, bh, bl)
  if aNeg ~= bNeg then return mkLong(negHalves(tobit(qh), tobit(ql))) end
  return fromU(qh, ql)
end

function R.lrem(a, b)
  checkDivisor(b)
  local ah, al, aNeg = absHalves(a)
  local bh, bl = absHalves(b)
  local _, _, rh, rl = udivmod(ah, al, bh, bl)
  if aNeg then return mkLong(negHalves(tobit(rh), tobit(rl))) end
  return fromU(rh, rl)
end

function R.ludiv(a, b)
  checkDivisor(b)
  local qh, ql = udivmod(u32(a.hi), u32(a.lo), u32(b.hi), u32(b.lo))
  return fromU(qh, ql)
end

function R.lurem(a, b)
  checkDivisor(b)
  local _, _, rh, rl = udivmod(u32(a.hi), u32(a.lo), u32(b.hi), u32(b.lo))
  return fromU(rh, rl)
end

function R.land(a, b) return mkLong(band(a.hi, b.hi), band(a.lo, b.lo)) end
function R.lor(a, b) return mkLong(bor(a.hi, b.hi), bor(a.lo, b.lo)) end
function R.lxor(a, b) return mkLong(bxor(a.hi, b.hi), bxor(a.lo, b.lo)) end

-- Shift counts are taken mod 64. For 0 < n < 32, the bits moving between the halves are computed
-- with exact multiplications and divisions by powers of 2.
function R.lshl(a, n)
  n = n % 64
  if n == 0 then return a end
  if n >= 32 then return mkLong(lshift(a.lo, n - 32), 0) end
  return mkLong(tobit((u32(a.hi) * POW2[n]) % TWO32 + floor(u32(a.lo) / POW2[32 - n])), lshift(a.lo, n))
end

-- Low half of a right shift by 0 < n < 32
local function shiftedLo(a, n)
  return tobit(floor(u32(a.lo) / POW2[n]) + (u32(a.hi) % POW2[n]) * POW2[32 - n])
end

function R.lshr(a, n)
  n = n % 64
  if n == 0 then return a end
  if n >= 32 then return mkLong(0, rshift(a.hi, n - 32)) end
  return mkLong(floor(u32(a.hi) / POW2[n]), shiftedLo(a, n))
end

function R.lsar(a, n)
  n = n % 64
  if n == 0 then return a end
  if n >= 32 then return mkLong(a.hi < 0 and -1 or 0, arshift(a.hi, n - 32)) end
  return mkLong(arshift(a.hi, n), shiftedLo(a, n))
end

function R.ltos(x)
  local d = R.l2d(x)
  if abs(d) < 9007199254740992 then return strformat("%.0f", d) end
  -- From 2^53: the last 9 digits apart (the others are then below 2^35)
  local h, l, negative = absHalves(x)
  local qh, ql, _, rl = udivmod(h, l, 0, 1000000000)
  return (negative and "-" or "") .. strformat("%.0f", qh * TWO32 + ql) .. strformat("%09d", rl)
end

-- ---------------------------------------------------------------------------
-- Floating point bits, with frexp/ldexp

local FLOAT_OVERFLOW = 2 ^ 128

-- Rounds to the nearest float, ties to even
function R.fround(x)
  if x ~= x or x == 0 or x == huge or x == -huge then return x end
  local m, e = frexp(abs(x))  -- abs(x) = m * 2^e, 0.5 <= m < 1
  -- A float has 24 significant bits, fewer when subnormal (its unit is 2^-149)
  local digits = e + 149
  if digits > 24 then digits = 24 end
  local r = ldexp(m, digits)
  local n = floor(r)
  local rest = r - n
  if rest > 0.5 or (rest == 0.5 and n % 2 == 1) then n = n + 1 end
  local y = ldexp(n, e - digits)
  if y >= FLOAT_OVERFLOW then y = huge end
  if x < 0 then return -y end
  return y
end

-- Biased exponent and significand bits of a finite positive number (`unit`: the exponent of the
-- smallest subnormal)
local function ieeeParts(a, significandBits, bias, unit)
  local m, e = frexp(a)
  local exp = e - 1 + bias
  if exp > 0 then return exp, ldexp(m * 2 - 1, significandBits) end
  return 0, ldexp(a, -unit)
end

local function isNegative(x) return x < 0 or (x == 0 and 1 / x < 0) end

function R.floatToBits(x)
  if x ~= x then return 0x7fc00000 end
  local sign = isNegative(x) and 2147483648 or 0
  local a = abs(x)
  local exp, significand = 0, 0
  if a == huge then exp = 255
  elseif a ~= 0 then exp, significand = ieeeParts(a, 23, 127, -149) end
  return tobit(sign + exp * 8388608 + significand)
end

function R.floatFromBits(i)
  local u = u32(i)
  local sign = 1
  if u >= 2147483648 then sign, u = -1, u - 2147483648 end
  local exp, significand = floor(u / 8388608), u % 8388608
  if exp == 255 then
    if significand ~= 0 then return 0 / 0 end
    return sign * huge
  end
  if exp == 0 then return sign * ldexp(significand, -149) end
  return sign * ldexp(significand + 8388608, exp - 150)
end

function R.doubleToBits(x)
  if x ~= x then return mkLong(0x7ff80000, 0) end
  local sign = isNegative(x) and 2147483648 or 0
  local a = abs(x)
  local exp, significand = 0, 0
  if a == huge then exp = 2047
  elseif a ~= 0 then exp, significand = ieeeParts(a, 52, 1023, -1074) end
  local high = floor(significand / 4294967296)
  return mkLong(tobit(sign + exp * 1048576 + high), tobit(significand - high * 4294967296))
end

function R.doubleFromBits(l)
  local hi, lo = u32(l.hi), u32(l.lo)
  local sign = 1
  if hi >= 2147483648 then sign, hi = -1, hi - 2147483648 end
  local exp = floor(hi / 1048576)
  local significand = (hi % 1048576) * 4294967296 + lo
  if exp == 2047 then
    if significand ~= 0 then return 0 / 0 end
    return sign * huge
  end
  if exp == 0 then return sign * ldexp(significand, -1074) end
  return sign * ldexp(significand + 4503599627370496, exp - 1075)
end
