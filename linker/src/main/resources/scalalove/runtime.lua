-- ScalaLove runtime (LuaJIT). Prepended to every generated file.
--
-- Value representation:
--   Int/Short/Byte/Char/Double/Float -> Lua number
--   Long                             -> LuaJIT int64_t cdata
--   Boolean                          -> Lua boolean
--   String                           -> Lua string (byte semantics)
--   null / undefined (Unit)          -> nil
--   boxed Char                       -> { c = code } with metatable R.CharBox
--   Scala objects                    -> tables whose metatable is their class table
--   Arrays                           -> tables { length = n, [0] = ..., [n-1] = ... }
local R = {}

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
local floor, ceil, huge, fmod = math.floor, math.ceil, math.huge, math.fmod
local type, getmetatable, setmetatable, rawget = type, getmetatable, setmetatable, rawget
local strbyte, strchar, strsub, strformat = string.byte, string.char, string.sub, string.format

R.bit, R.ffi = bit, ffi

local C, MOD, LOAD = {}, {}, {}
R.C, R.MOD, R.LOAD = C, MOD, LOAD

-- ---------------------------------------------------------------------------
-- Classes

local classesByName = {}

-- Declares a class table. `ancestors` lists all ancestor names (incl. itself).
function R.declare(name, kind, ancestors)
  local P = { __name = name, __kind = kind, __anc = {} }
  P.__index = P
  for i = 1, #ancestors do P.__anc[ancestors[i]] = true end
  classesByName[name] = P
  return P
end

-- Copies down inherited members (superclass first, then interfaces from the
-- most specific to the least specific) into P.
function R.inherit(P, super, ifaces)
  local function copy(from)
    for k, v in pairs(from) do
      if P[k] == nil and type(k) == "string" and strsub(k, 1, 2) ~= "__" then P[k] = v end
    end
  end
  if super then copy(super) end
  if ifaces then for i = 1, #ifaces do copy(ifaces[i]) end end
end

-- ---------------------------------------------------------------------------
-- Errors / exceptions

-- Set by the generated code: wraps a caught Lua error into a Throwable
-- (JavaScriptException for non-Scala values).
R.wrapJSException = nil

function R.wrapT(e)
  if type(e) == "table" then
    local mt = getmetatable(e)
    if mt and mt.__anc and mt.__anc["java.lang.Throwable"] then return e end
  end
  return R.wrapJSException(e)
end

function R.unwrapT(e)
  if R.unwrapJSException then return R.unwrapJSException(e) end
  return e
end

R.arithmeticException = function(msg) error("ArithmeticException: " .. msg, 2) end

-- Unique marker returned from pcall'ed try-blocks to jump to an outer label.
R.JMP = setmetatable({}, { __tostring = function() return "<jump>" end })

-- ---------------------------------------------------------------------------
-- Int arithmetic (Java semantics on 32-bit integers stored in doubles)

function R.imul(a, b)
  local al, ah = band(a, 0xffff), rshift(a, 16)
  return tobit(al * b + lshift(ah * b, 16))
end

function R.idiv(a, b)
  if b == 0 then R.arithmeticException("/ by zero") end
  local q = a / b
  if q >= 0 then q = floor(q) else q = ceil(q) end
  return tobit(q)
end

function R.irem(a, b)
  if b == 0 then R.arithmeticException("/ by zero") end
  return tobit(fmod(a, b))
end

local function toUnsigned(a) if a < 0 then return a + 4294967296 else return a end end
R.toUnsigned = toUnsigned

function R.iudiv(a, b)
  if b == 0 then R.arithmeticException("/ by zero") end
  return tobit(floor(toUnsigned(a) / toUnsigned(b)))
end

function R.iurem(a, b)
  if b == 0 then R.arithmeticException("/ by zero") end
  return tobit(fmod(toUnsigned(a), toUnsigned(b)))
end

function R.d2i(x)
  if x ~= x then return 0 end
  if x >= 2147483647 then return 2147483647 end
  if x <= -2147483648 then return -2147483648 end
  if x >= 0 then return floor(x) else return ceil(x) end
end

-- JS ToInt32
function R.toInt32(x)
  if x ~= x or x == huge or x == -huge then return 0 end
  if x >= 0 then x = floor(x) else x = ceil(x) end
  return tobit(fmod(x, 4294967296))
end

function R.clz(x)
  if x == 0 then return 32 end
  local n = 0
  if band(x, 0xffff0000) == 0 then n = n + 16; x = lshift(x, 16) end
  if band(x, 0xff000000) == 0 then n = n + 8; x = lshift(x, 8) end
  if band(x, 0xf0000000) == 0 then n = n + 4; x = lshift(x, 4) end
  if band(x, 0xc0000000) == 0 then n = n + 2; x = lshift(x, 2) end
  if band(x, 0x80000000) == 0 then n = n + 1 end
  return n
end

-- ---------------------------------------------------------------------------
-- Long arithmetic (int64_t cdata)

local int64 = ffi.typeof("int64_t")
local uint64 = ffi.typeof("uint64_t")
R.int64 = int64

local LMIN = -9223372036854775807LL - 1LL
local LMAX = 9223372036854775807LL

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
-- Floating point

local fbox = ffi.new("float[1]")
function R.fround(x) fbox[0] = x; return tonumber(fbox[0]) end

local bits = ffi.new("union { double d; int64_t l; float f; int32_t i; }")
function R.doubleToBits(x) bits.d = x; return bits.l end
function R.doubleFromBits(l) bits.l = l; return bits.d end
function R.floatToBits(x) bits.f = x; return tonumber(bits.i) end
function R.floatFromBits(i) bits.i = i; return tonumber(bits.f) end

-- JS-like Number.prototype.toString()
function R.numToStr(x)
  if x ~= x then return "NaN" end
  if x == huge then return "Infinity" elseif x == -huge then return "-Infinity" end
  if x == 0 then return "0" end
  if x == floor(x) and x < 1e21 and x > -1e21 then return strformat("%.0f", x) end
  local s
  for p = 15, 17 do
    s = strformat("%." .. p .. "g", x)
    if tonumber(s) == x then break end
  end
  -- JS style exponent: 1e+21, 1.5e-7
  s = s:gsub("e([+-])0*(%d)", "e%1%2")
  return s
end

-- ---------------------------------------------------------------------------
-- Strings

local function utf8char(c)
  if c < 0x80 then return strchar(c)
  elseif c < 0x800 then return strchar(0xC0 + rshift(c, 6), 0x80 + band(c, 0x3F))
  elseif c < 0x10000 then
    return strchar(0xE0 + rshift(c, 12), 0x80 + band(rshift(c, 6), 0x3F), 0x80 + band(c, 0x3F))
  else
    return strchar(0xF0 + rshift(c, 18), 0x80 + band(rshift(c, 12), 0x3F),
      0x80 + band(rshift(c, 6), 0x3F), 0x80 + band(c, 0x3F))
  end
end
R.utf8char = utf8char

-- Char -> String. Strings are byte strings: chars < 256 map to one byte.
function R.chr(c)
  if c < 256 then return strchar(c) end
  return utf8char(c)
end

local TOSTRING -- method ident of toString(), set by the generated code
function R.setToStringName(n) TOSTRING = n end

-- Converts any value to a string, as `"" + x` would in Scala.js
function R.str(x)
  local t = type(x)
  if t == "string" then return x
  elseif t == "number" then return R.numToStr(x)
  elseif t == "boolean" then return x and "true" or "false"
  elseif x == nil then return "null"
  elseif t == "cdata" then return R.ltos(x)
  elseif t == "table" then
    local mt = getmetatable(x)
    if mt == R.CharBox then return R.chr(x.c) end
    if mt and mt[TOSTRING] then return x[TOSTRING](x) end
  end
  return tostring(x)
end

-- If `regex` (a Java regex) only matches a literal string, returns that string.
function R.regexLiteral(regex)
  local out = {}
  local i, n = 1, #regex
  while i <= n do
    local c = strsub(regex, i, i)
    if c == "\\" then
      local d = strsub(regex, i + 1, i + 1)
      if d == "Q" then
        local e = string.find(regex, "\\E", i + 2, true)
        out[#out + 1] = strsub(regex, i + 2, (e or n + 1) - 1)
        i = e and e + 2 or n + 1
      elseif d == "t" then out[#out + 1] = "\t"; i = i + 2
      elseif d == "n" then out[#out + 1] = "\n"; i = i + 2
      elseif d:match("^[%w]$") then return nil
      else out[#out + 1] = d; i = i + 2 end
    elseif c:match("^[%^%$%.%|%?%*%+%(%)%[%]{}]$") then
      return nil
    else
      out[#out + 1] = c
      i = i + 1
    end
  end
  return table.concat(out)
end

-- java.lang.String.split, for regexes matching a literal string.
-- Returns a 0-based table of parts and its length.
function R.split(s, regex, limit)
  local sep = R.regexLiteral(regex)
  if sep == nil then error("ScalaLove: String.split only supports literal separators for now: " .. regex, 2) end
  local parts, n = {}, 0
  if sep == "" then
    for i = 1, #s do parts[n] = strsub(s, i, i); n = n + 1 end
    if n == 0 then parts[0] = ""; n = 1 end
  else
    local start = 1
    while limit <= 0 or n < limit - 1 do
      local i, j = string.find(s, sep, start, true)
      if not i then break end
      parts[n] = strsub(s, start, i - 1)
      n = n + 1
      start = j + 1
    end
    parts[n] = strsub(s, start)
    n = n + 1
  end
  if limit == 0 then
    while n > 1 and parts[n - 1] == "" do parts[n - 1] = nil; n = n - 1 end
    if n == 1 and parts[0] == "" and #s > 0 then parts[0] = nil; n = 0 end
  end
  return parts, n
end

-- ---------------------------------------------------------------------------
-- Boxing / dispatch on hijacked classes

local CharBox = { __name = "java.lang.Character" }
R.CharBox = CharBox
function R.boxC(c) return setmetatable({ c = c }, CharBox) end
function R.unboxC(x) if x == nil then return 0 end return x.c end

-- Hijacked class tables, filled in by the generated code
local H = {}
R.H = H

-- Calls method `m` on `x`, which may be a primitive (hijacked) value.
function R.dp(x, m, ...)
  local t = type(x)
  if t == "table" then
    if getmetatable(x) == CharBox then return H.char[m](x.c, ...) end
    return x[m](x, ...)
  elseif t == "string" then return H.string[m](x, ...)
  elseif t == "number" then return H.number[m](x, ...)
  elseif t == "boolean" then return H.boolean[m](x, ...)
  elseif t == "cdata" then return H.long[m](x, ...)
  elseif x == nil then return H.unit[m](x, ...)
  end
  return x[m](x, ...)
end

-- ---------------------------------------------------------------------------
-- Type tests

function R.isInt(x) return type(x) == "number" and tobit(x) == x and (x ~= 0 or 1 / x > 0) end
function R.isByte(x) return R.isInt(x) and x >= -128 and x <= 127 end
function R.isShort(x) return R.isInt(x) and x >= -32768 and x <= 32767 end
function R.isFloat(x) return type(x) == "number" and (x ~= x or R.fround(x) == x) end
function R.isLong(x) return type(x) == "cdata" and ffi.istype(int64, x) end
function R.isChar(x) return type(x) == "table" and getmetatable(x) == CharBox end

-- Ancestors of the hijacked classes, filled in by the generated code
R.hijackedAncestors = { string = {}, number = {}, boolean = {}, long = {}, char = {}, unit = {} }

-- x instanceof the class/interface with the given name
function R.isInstance(x, name)
  local t = type(x)
  if t == "table" then
    local mt = getmetatable(x)
    if mt == CharBox then return R.hijackedAncestors.char[name] == true end
    if mt ~= nil and mt.__arrBase then
      return name == "java.lang.Object" or name == "java.lang.Cloneable" or name == "java.io.Serializable"
    end
    return mt ~= nil and mt.__anc ~= nil and mt.__anc[name] == true
  elseif t == "string" then return R.hijackedAncestors.string[name] == true
  elseif t == "number" then return R.hijackedAncestors.number[name] == true
  elseif t == "boolean" then return R.hijackedAncestors.boolean[name] == true
  elseif t == "cdata" then return R.hijackedAncestors.long[name] == true
  end
  return false
end

-- ---------------------------------------------------------------------------
-- Arrays

local arrayTypes = {}

-- Returns the metatable for arrays with the given base type descriptor
-- ("I", "Z", ..., or a class name) and number of dimensions.
function R.arrayType(base, dims, zero)
  local key = base .. "/" .. dims
  local T = arrayTypes[key]
  if not T then
    local name = string.rep("[", dims) .. (#base == 1 and base or ("L" .. base .. ";"))
    T = { __name = name, __arrBase = base, __arrDims = dims, __zero = zero }
    T.__index = function(_, k) return C.java_lang_Object and C.java_lang_Object[k] end
    arrayTypes[key] = T
  end
  return T
end

function R.newArray(T, n)
  local a = setmetatable({ length = n }, T)
  local z = T.__zero
  if z ~= nil then for i = 0, n - 1 do a[i] = z end end
  return a
end

function R.arrayOf(T, a, n)
  a.length = n
  return setmetatable(a, T)
end

function R.isArrayOf(x, base, dims)
  if type(x) ~= "table" then return false end
  local mt = getmetatable(x)
  if not mt or not mt.__arrBase then return false end
  if mt.__arrDims == dims then
    if mt.__arrBase == base then return true end
    if #base == 1 or #mt.__arrBase == 1 then return false end
    local P = classesByName[mt.__arrBase]
    return base == "java.lang.Object" or (P ~= nil and P.__anc[base] == true)
  elseif mt.__arrDims > dims then
    return base == "java.lang.Object" or base == "java.io.Serializable" or base == "java.lang.Cloneable"
  end
  return false
end

function R.clone(x)
  local mt = getmetatable(x)
  local y = {}
  for k, v in pairs(x) do y[k] = v end
  return setmetatable(y, mt)
end

function R.arraycopy(src, srcPos, dest, destPos, len)
  if src == dest and srcPos < destPos then
    for i = len - 1, 0, -1 do dest[destPos + i] = src[srcPos + i] end
  else
    for i = 0, len - 1 do dest[destPos + i] = src[srcPos + i] end
  end
end

-- ---------------------------------------------------------------------------
-- Identity hash codes

local idHashes = setmetatable({}, { __mode = "k" })
local lastIdHash = 0
function R.ihc(x)
  local t = type(x)
  if t == "table" and getmetatable(x) ~= CharBox then
    local h = idHashes[x]
    if not h then
      lastIdHash = tobit(lastIdHash + 0x61c88647)
      h = lastIdHash
      idHashes[x] = h
    end
    return h
  elseif x == nil then
    return 0
  end
  return R.dp(x, R.hashCodeName)
end

-- ---------------------------------------------------------------------------
-- java.lang.Class

local classObjects = {}
local primNames = {
  V = "void", Z = "boolean", C = "char", B = "byte", S = "short", I = "int",
  J = "long", F = "float", D = "double",
}

-- name: a class name, a primitive char code, or an array name like "[I"
function R.classOf(name)
  local c = classObjects[name]
  if not c then
    local P = classesByName[name]
    c = {
      name = primNames[name] or name,
      isPrimitive = primNames[name] ~= nil,
      isInterface = P ~= nil and P.__kind == "interface",
      isArrayClass = strsub(name, 1, 1) == "[",
      __desc = name,
    }
    setmetatable(c, C.java_lang_Class)
    classObjects[name] = c
  end
  return c
end

function R.getClass(x)
  local t = type(x)
  if t == "string" then return R.classOf("java.lang.String")
  elseif t == "number" then
    if R.isInt(x) then return R.classOf("java.lang.Integer") end
    return R.classOf("java.lang.Double")
  elseif t == "boolean" then return R.classOf("java.lang.Boolean")
  elseif t == "cdata" then return R.classOf("java.lang.Long")
  elseif x == nil then return R.classOf("java.lang.Void")
  end
  local mt = getmetatable(x)
  if mt == CharBox then return R.classOf("java.lang.Character") end
  if mt and mt.__name then return R.classOf(mt.__name) end
  return nil
end

-- ---------------------------------------------------------------------------
-- JavaScript interop emulation
--
-- The Scala.js standard library (javalib) calls a few JavaScript built-ins.
-- We emulate the ones it needs. Convention: JS methods receive `this` as
-- their first parameter, like Lua methods called with `:`.

local js = {}
R.js = js
setmetatable(js, { __index = function(_, k) error("JavaScript global '" .. tostring(k) .. "' is not available in Lua", 2) end })

js.undefined = nil
js.console = {
  log = function(...) print(...) end,
  info = function(...) print(...) end,
  warn = function(...) io.stderr:write(table.concat({ ... }, " "), "\n") end,
  error = function(...) io.stderr:write(table.concat({ ... }, " "), "\n") end,
}
js.NaN = 0 / 0
js.Infinity = huge

local function jsTypeof(x)
  local t = type(x)
  if t == "nil" then return "undefined"
  elseif t == "number" then return "number"
  elseif t == "string" then return "string"
  elseif t == "boolean" then return "boolean"
  elseif t == "function" then return "function"
  elseif t == "cdata" then return "bigint"
  end
  return "object"
end
R.typeof = jsTypeof

local function trunc(x) if x >= 0 then return floor(x) else return ceil(x) end end

js.Math = {
  PI = math.pi, E = math.exp(1),
  abs = math.abs, floor = floor, ceil = ceil, sqrt = math.sqrt,
  sin = math.sin, cos = math.cos, tan = math.tan, asin = math.asin, acos = math.acos,
  atan = math.atan, atan2 = math.atan2, exp = math.exp, log = math.log,
  log10 = math.log10, pow = math.pow,
  max = function(a, b) if a ~= a or b ~= b then return 0 / 0 end if a > b then return a end return b end,
  min = function(a, b) if a ~= a or b ~= b then return 0 / 0 end if a < b then return a end return b end,
  random = math.random,
  round = function(x) return floor(x + 0.5) end,
  trunc = trunc,
  sign = function(x) if x > 0 then return 1 elseif x < 0 then return -1 end return x end,
  fround = R.fround, imul = R.imul, clz32 = R.clz,
  hypot = function(a, b) return math.sqrt(a * a + b * b) end,
  cbrt = function(x) if x < 0 then return -((-x) ^ (1 / 3)) end return x ^ (1 / 3) end,
  expm1 = function(x) return math.exp(x) - 1 end,
  log1p = function(x) return math.log(1 + x) end,
  sinh = math.sinh, cosh = math.cosh, tanh = math.tanh,
  log2 = function(x) return math.log(x) / math.log(2) end,
}

js.Object = {
  prototype = {
    hasOwnProperty = function(this, k) return type(this) == "table" and rawget(this, k) ~= nil end,
    toString = function(this) return "[object Object]" end,
  },
  isSealed = function(_) return false end,
  is = function(a, b)
    if a ~= a and b ~= b then return true end
    if a == 0 and b == 0 then return 1 / a == 1 / b end
    return a == b
  end,
  keys = function(o) return R.objectKeys(o) end,
}

js.Error = {
  new = function(msg)
    return { message = msg, stack = debug.traceback(msg and tostring(msg) or "", 3) }
  end,
}

-- Wall clock: LuaSocket's (bundled with LÖVE) when available
local hasSocket, luasocket = pcall(require, "socket")
local wallClock = hasSocket and luasocket.gettime or os.time
js.performance = { now = function() return wallClock() * 1000 end }
js.Date = { now = function() return math.floor(wallClock() * 1000) end }

js.Number = {
  isNaN = function(x) return x ~= x end,
  isFinite = function(x) return x == x and x ~= huge and x ~= -huge end,
  isInteger = function(x) return x == floor(x) and x ~= huge and x ~= -huge end,
  MAX_SAFE_INTEGER = 9007199254740991,
}
js.isNaN = js.Number.isNaN
js.isFinite = js.Number.isFinite
js.parseFloat = function(s) return tonumber(s) or 0 / 0 end
js.parseInt = function(s, radix) return tonumber(s, radix or 10) or 0 / 0 end

js.String = {
  fromCharCode = function(...)
    local n = select("#", ...)
    local parts = {}
    for i = 1, n do parts[i] = R.chr((select(i, ...))) end
    return table.concat(parts)
  end,
}

-- JS arrays: like Scala arrays, 0-based tables with an explicit `length`.
local JSArray = { __name = "js.Array" }
JSArray.__index = JSArray
JSArray.__newindex = function(t, k, v)
  if type(k) == "number" and k >= t.length then rawset(t, "length", k + 1) end
  rawset(t, k, v)
end
R.JSArray = JSArray

function R.jarr(t, n)
  t.length = n
  return setmetatable(t, JSArray)
end

-- Packs Lua varargs into a JS array
function R.restArgs(...)
  local n = select("#", ...)
  local a = { length = n }
  for i = 1, n do a[i - 1] = (select(i, ...)) end
  return setmetatable(a, JSArray)
end

-- Spreads a 0-based array as Lua varargs
function R.unpackArr(a)
  return unpack(a, 0, a.length - 1)
end

function JSArray.push(a, ...)
  for i = 1, select("#", ...) do
    local n = a.length
    rawset(a, n, (select(i, ...)))
    a.length = n + 1
  end
  return a.length
end
function JSArray.pop(a)
  local n = a.length
  if n == 0 then return nil end
  local v = a[n - 1]
  rawset(a, n - 1, nil)
  a.length = n - 1
  return v
end
function JSArray.shift(a)
  local n = a.length
  if n == 0 then return nil end
  local v = a[0]
  for i = 1, n - 1 do rawset(a, i - 1, a[i]) end
  rawset(a, n - 1, nil)
  a.length = n - 1
  return v
end
function JSArray.unshift(a, ...)
  local k = select("#", ...)
  local n = a.length
  for i = n - 1, 0, -1 do rawset(a, i + k, a[i]) end
  for i = 1, k do rawset(a, i - 1, (select(i, ...))) end
  a.length = n + k
  return a.length
end
local function relIdx(i, len, default)
  if i == nil then return default end
  if i < 0 then i = len + i; if i < 0 then i = 0 end end
  if i > len then i = len end
  return i
end
function JSArray.slice(a, s, e)
  local len = a.length
  s = relIdx(s, len, 0)
  e = relIdx(e, len, len)
  local r = { length = 0 }
  for i = s, e - 1 do r[i - s] = a[i] end
  r.length = math.max(e - s, 0)
  return setmetatable(r, JSArray)
end
function JSArray.splice(a, start, deleteCount, ...)
  local len = a.length
  start = relIdx(start, len, 0)
  if deleteCount == nil then deleteCount = len - start end
  deleteCount = math.max(0, math.min(deleteCount, len - start))
  local removed = { length = deleteCount }
  for i = 0, deleteCount - 1 do removed[i] = a[start + i] end
  local items = select("#", ...)
  local tail = {}
  for i = start + deleteCount, len - 1 do tail[#tail + 1] = a[i] end
  for i = start, len - 1 do rawset(a, i, nil) end
  local pos = start
  for i = 1, items do rawset(a, pos, (select(i, ...))); pos = pos + 1 end
  for i = 1, #tail do rawset(a, pos, tail[i]); pos = pos + 1 end
  -- holes (nil elements) in `tail` are handled by the explicit length
  a.length = len - deleteCount + items
  return setmetatable(removed, JSArray)
end
function JSArray.concat(a, ...)
  local r = a:slice()
  for i = 1, select("#", ...) do
    local b = (select(i, ...))
    if type(b) == "table" and getmetatable(b) == JSArray then
      for j = 0, b.length - 1 do r:push(b[j]) end
    else
      r:push(b)
    end
  end
  return r
end
function JSArray.join(a, sep)
  local parts = {}
  for i = 0, a.length - 1 do
    local v = a[i]
    parts[i + 1] = v == nil and "" or R.str(v)
  end
  return table.concat(parts, sep or ",")
end
function JSArray.indexOf(a, v)
  for i = 0, a.length - 1 do if a[i] == v then return i end end
  return -1
end
function JSArray.reverse(a)
  local n = a.length
  for i = 0, math.floor(n / 2) - 1 do
    a[i], a[n - 1 - i] = a[n - 1 - i], a[i]
  end
  return a
end
function JSArray.fill(a, v, s, e)
  for i = relIdx(s, a.length, 0), relIdx(e, a.length, a.length) - 1 do rawset(a, i, v) end
  return a
end
function JSArray.forEach(a, f) for i = 0, a.length - 1 do f(a[i], i) end end
function JSArray.map(a, f)
  local r = { length = a.length }
  for i = 0, a.length - 1 do r[i] = f(a[i], i) end
  return setmetatable(r, JSArray)
end
function JSArray.sort(a, cmp)
  local t = {}
  for i = 0, a.length - 1 do t[i + 1] = a[i] end
  table.sort(t, cmp and function(x, y) return cmp(x, y) < 0 end or function(x, y) return R.str(x) < R.str(y) end)
  for i = 1, #t do rawset(a, i - 1, t[i]) end
  return a
end

js.Array = {
  isArray = function(x) return type(x) == "table" and getmetatable(x) == JSArray end,
  new = function(n)
    return setmetatable({ length = n or 0 }, JSArray)
  end,
}

-- ---------------------------------------------------------------------------
-- JSON
--
-- JS objects are Lua tables, which cannot hold nil values and do not keep their keys in order.
-- Objects created by JSON.parse remember their keys (in order, including the ones whose value is
-- null) in `objectKeyOrder` (a list, plus a set in `objectKeySet`), which Object.keys and
-- hasOwnProperty use.

local objectKeyOrder = setmetatable({}, { __mode = "k" })
local objectKeySet = setmetatable({}, { __mode = "k" })

js.Object.prototype.hasOwnProperty = function(this, k)
  if type(this) ~= "table" then return false end
  if rawget(this, k) ~= nil then return true end
  local set = objectKeySet[this]
  return set ~= nil and set[k] == true
end

function R.objectKeys(o)
  local keys = objectKeyOrder[o]
  local result = { length = 0 }
  if keys then
    for i = 1, #keys do result[i - 1] = keys[i] end
    result.length = #keys
  elseif type(o) == "table" then
    local sorted = {}
    for k in pairs(o) do
      if type(k) == "string" and not (getmetatable(o) == JSArray and k == "length") then sorted[#sorted + 1] = k end
    end
    table.sort(sorted)
    for i = 1, #sorted do result[i - 1] = sorted[i] end
    result.length = #sorted
  end
  return setmetatable(result, JSArray)
end

local jsonEscapes = { ['"'] = '"', ["\\"] = "\\", ["/"] = "/", b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }

local function parseJSON(s)
  local pos = 1
  local function fail(message)
    error("SyntaxError: JSON.parse: " .. message .. " at position " .. (pos - 1), 0)
  end
  local function skip()
    pos = string.find(s, "[^ \t\r\n]", pos) or (#s + 1)
  end
  local parseValue

  local function parseString()
    pos = pos + 1 -- opening quote
    local parts = {}
    while true do
      local j = string.find(s, '["\\]', pos)
      if not j then fail("unterminated string") end
      if j > pos then parts[#parts + 1] = strsub(s, pos, j - 1) end
      pos = j
      if strsub(s, j, j) == '"' then
        pos = j + 1
        return table.concat(parts)
      end
      local e = strsub(s, j + 1, j + 1)
      if e == "u" then
        local cp = tonumber(strsub(s, j + 2, j + 5), 16)
        if not cp then fail("bad unicode escape") end
        pos = j + 6
        if cp >= 0xD800 and cp <= 0xDBFF and strsub(s, pos, pos + 1) == "\\u" then
          local low = tonumber(strsub(s, pos + 2, pos + 5), 16)
          if low and low >= 0xDC00 and low <= 0xDFFF then
            cp = 0x10000 + (cp - 0xD800) * 0x400 + (low - 0xDC00)
            pos = pos + 6
          end
        end
        parts[#parts + 1] = utf8char(cp)
      else
        local c = jsonEscapes[e]
        if not c then fail("bad escape") end
        parts[#parts + 1] = c
        pos = j + 2
      end
    end
  end

  local function parseNumber()
    local text = string.match(s, "^-?%d+%.?%d*[eE]?[-+]?%d*", pos)
    local n = text and tonumber(text)
    if not n then fail("bad number") end
    pos = pos + #text
    return n
  end

  function parseValue()
    skip()
    local c = strsub(s, pos, pos)
    if c == "{" then
      pos = pos + 1
      local o, keys, set = {}, {}, {}
      skip()
      if strsub(s, pos, pos) == "}" then
        pos = pos + 1
      else
        while true do
          skip()
          if strsub(s, pos, pos) ~= '"' then fail("expected a string key") end
          local k = parseString()
          skip()
          if strsub(s, pos, pos) ~= ":" then fail("expected ':'") end
          pos = pos + 1
          local v = parseValue()
          if not set[k] then
            set[k] = true
            keys[#keys + 1] = k
          end
          o[k] = v
          skip()
          local d = strsub(s, pos, pos)
          pos = pos + 1
          if d == "}" then break elseif d ~= "," then fail("expected ',' or '}'") end
        end
      end
      objectKeyOrder[o] = keys
      objectKeySet[o] = set
      return o
    elseif c == "[" then
      pos = pos + 1
      local a = { length = 0 }
      skip()
      if strsub(s, pos, pos) == "]" then
        pos = pos + 1
      else
        local n = 0
        while true do
          a[n] = parseValue()
          n = n + 1
          skip()
          local d = strsub(s, pos, pos)
          pos = pos + 1
          if d == "]" then break elseif d ~= "," then fail("expected ',' or ']'") end
        end
        a.length = n
      end
      return setmetatable(a, JSArray)
    elseif c == '"' then
      return parseString()
    elseif c == "-" or string.find(c, "%d") then
      return parseNumber()
    elseif strsub(s, pos, pos + 3) == "true" then
      pos = pos + 4
      return true
    elseif strsub(s, pos, pos + 4) == "false" then
      pos = pos + 5
      return false
    elseif strsub(s, pos, pos + 3) == "null" then
      pos = pos + 4
      return nil
    end
    fail("unexpected " .. (c == "" and "end of input" or ("'" .. c .. "'")))
  end

  local value = parseValue()
  skip()
  if pos <= #s then fail("unexpected trailing characters") end
  return value
end

local function quoteJSON(str)
  return '"' .. str:gsub('[%c"\\]', function(c)
    if c == '"' then return '\\"' elseif c == "\\" then return "\\\\"
    elseif c == "\n" then return "\\n" elseif c == "\r" then return "\\r" elseif c == "\t" then return "\\t"
    else return strformat("\\u%04x", strbyte(c)) end
  end) .. '"'
end

local function stringifyJSON(v)
  local t = type(v)
  if v == nil then return "null"
  elseif t == "boolean" then return v and "true" or "false"
  elseif t == "number" then
    if v ~= v or v == huge or v == -huge then return "null" end
    return R.numToStr(v)
  elseif t == "string" then return quoteJSON(v)
  elseif t == "table" then
    if getmetatable(v) == JSArray then
      local parts = {}
      for i = 0, v.length - 1 do parts[#parts + 1] = stringifyJSON(v[i]) end
      return "[" .. table.concat(parts, ",") .. "]"
    end
    local keys = R.objectKeys(v)
    local parts = {}
    for i = 0, keys.length - 1 do
      local k = keys[i]
      if v[k] ~= nil then parts[#parts + 1] = quoteJSON(k) .. ":" .. stringifyJSON(v[k]) end
    end
    return "{" .. table.concat(parts, ",") .. "}"
  end
  return "null"
end

js.JSON = {
  parse = function(text) return parseJSON(text) end,
  stringify = function(value) return stringifyJSON(value) end,
}

-- JS `instanceof`
function R.jinstanceof(x, ctor)
  if ctor == js.Array then return type(x) == "table" and getmetatable(x) == JSArray end
  if ctor == js.Object then
    local t = type(x)
    return t == "table" or t == "userdata" or t == "function" or t == "cdata"
  end
  if type(ctor) == "table" and ctor.isInstance then return ctor.isInstance(x) end
  return false
end

-- Methods of JS strings, numbers and functions (when called with a receiver)
local JSString = {}
local JSNumber = {}
local JSFunction = {}

local function clampIdx(i, len)
  if i == nil then return 0 end
  if i ~= i then return 0 end
  if i < 0 then return 0 elseif i > len then return len end
  return trunc(i)
end

function JSString.substring(s, a, b)
  local len = #s
  a = clampIdx(a, len)
  b = b == nil and len or clampIdx(b, len)
  if a > b then a, b = b, a end
  return strsub(s, a + 1, b)
end
function JSString.slice(s, a, b)
  local len = #s
  a = a or 0
  if a < 0 then a = math.max(len + a, 0) end
  b = b == nil and len or b
  if b < 0 then b = math.max(len + b, 0) end
  if a >= b then return "" end
  return strsub(s, a + 1, b)
end
function JSString.charCodeAt(s, i) return strbyte(s, i + 1) or 0 / 0 end
JSString.codePointAt = JSString.charCodeAt
function JSString.charAt(s, i) return strsub(s, i + 1, i + 1) end
function JSString.indexOf(s, sub, from)
  local r = string.find(s, sub, (from or 0) + 1, true)
  return r and (r - 1) or -1
end
function JSString.lastIndexOf(s, sub, from)
  local last = -1
  local start = 1
  local limit = from and (from + 1) or #s + 1
  while true do
    local r = string.find(s, sub, start, true)
    if not r or r > limit then break end
    last = r - 1
    start = r + 1
  end
  return last
end
function JSString.toUpperCase(s) return string.upper(s) end
function JSString.toLowerCase(s) return string.lower(s) end
JSString.toLocaleUpperCase = JSString.toUpperCase
JSString.toLocaleLowerCase = JSString.toLowerCase
function JSString.trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end
function JSString.startsWith(s, p, pos) pos = pos or 0; return strsub(s, pos + 1, pos + #p) == p end
function JSString.endsWith(s, p) return p == "" or strsub(s, -#p) == p end
function JSString.includes(s, p) return string.find(s, p, 1, true) ~= nil end
function JSString.concat(s, ...) return s .. table.concat({ ... }) end
function JSString.toString(s) return s end
function JSString.valueOf(s) return s end
function JSString.localeCompare(a, b) if a < b then return -1 elseif a > b then return 1 end return 0 end
JSString["repeat"] = function(s, n) return string.rep(s, n) end

function JSNumber.toString(x, base)
  if base == nil or base == 10 then return R.numToStr(x) end
  if x ~= floor(x) then error("Number.toString(radix) only supports integers here") end
  local digits = "0123456789abcdefghijklmnopqrstuvwxyz"
  local neg = x < 0
  if neg then x = -x end
  if x == 0 then return "0" end
  local out = {}
  while x > 0 do
    local d = x % base
    out[#out + 1] = strsub(digits, d + 1, d + 1)
    x = floor(x / base)
  end
  local s = string.reverse(table.concat(out))
  if neg then s = "-" .. s end
  return s
end
function JSNumber.toFixed(x, n) return strformat("%." .. (n or 0) .. "f", x) end
function JSNumber.valueOf(x) return x end

function JSFunction.call(f, thisArg, ...) return f(thisArg, ...) end

-- o.m(args) for a JS value o (string, number, Lua object, userdata, ...)
function R.jcall(o, m, ...)
  local t = type(o)
  local f
  if t == "string" then f = JSString[m]
  elseif t == "number" then f = JSNumber[m]
  elseif t == "function" then f = JSFunction[m]
  else return o[m](o, ...) end
  if f == nil then error("JS method '" .. tostring(m) .. "' is not available on a " .. t, 2) end
  return f(o, ...)
end

-- o[k] for a JS value o
function R.jget(o, k)
  local t = type(o)
  if t == "table" or t == "userdata" then
    local v = o[k]
    -- In JS, a["0"] is a[0]
    if v == nil and type(k) == "string" and t == "table" and getmetatable(o) == JSArray then
      local i = tonumber(k)
      if i then return o[i] end
    end
    return v
  end
  if t == "string" then
    if k == "length" then return #o end
    return JSString[k]
  end
  if t == "number" then return JSNumber[k] end
  if t == "function" then return JSFunction[k] end
  error("cannot read property '" .. tostring(k) .. "' of " .. tostring(o), 2)
end

function R.jnew(ctor, ...)
  if type(ctor) == "table" and ctor.new then return ctor.new(...) end
  error("cannot instantiate JS class " .. tostring(ctor), 2)
end

-- JS `+`
function R.jadd(a, b)
  if type(a) == "string" or type(b) == "string" then return R.str(a) .. R.str(b) end
  return a + b
end

function R.jin(k, o) return type(o) == "table" and o[k] ~= nil end

-- JS truthiness
function R.truthy(x)
  return not (x == nil or x == false or x == 0 or x == "" or x ~= x)
end

-- Packs the values returned by a Lua function into a Scala tuple.
-- `P` is the TupleN class table and `ctor` its constructor taking N values.
function R.tuple(P, ctor, ...)
  local first = ...
  -- A function that already returns a Scala tuple
  if select("#", ...) == 1 and type(first) == "table" and getmetatable(first) == P then return first end
  return P[ctor](P.__new(), ...)
end

-- Utilities available to Lua code and facades as the `scalalove` global
scalalove = scalalove or {}

-- Lua (binary) string -> Scala Array[Byte]
function scalalove.stringToBytes(s)
  local n = #s
  local a = { length = n }
  local i = 1
  while i <= n do
    local j = math.min(i + 4095, n)
    local chunk = { strbyte(s, i, j) }
    for k = 1, #chunk do
      local b = chunk[k]
      if b > 127 then b = b - 256 end
      a[i + k - 2] = b
    end
    i = j + 1
  end
  return setmetatable(a, R.arrayType("B", 1, 0))
end

-- Scala Array[Byte] -> Lua (binary) string
function scalalove.bytesToString(a)
  local n = a.length
  local parts = {}
  local i = 0
  while i < n do
    local j = math.min(i + 4095, n - 1)
    local chunk = {}
    for k = i, j do chunk[k - i + 1] = band(a[k], 255) end
    parts[#parts + 1] = strchar(unpack(chunk))
    i = j + 1
  end
  return table.concat(parts)
end

function R.zor(a, b) return a or b end
function R.zand(a, b) return a and b end
function R.void(_) return nil end
function R.unsupported(what) error("ScalaLove: unsupported operation: " .. tostring(what), 2) end
-- Array type descriptors ("[I", "[Lfoo.Bar;") -> (base, dims)
local function parseArrayDesc(desc)
  local dims = 0
  while strsub(desc, dims + 1, dims + 1) == "[" do dims = dims + 1 end
  local rest = strsub(desc, dims + 1)
  if strsub(rest, 1, 1) == "L" then rest = strsub(rest, 2, -2) end
  return rest, dims
end
local zeros = { Z = false, J = 0LL, C = 0, B = 0, S = 0, I = 0, F = 0, D = 0 }

function R.classComponentType(c)
  local d = c.__desc
  if strsub(d, 1, 1) ~= "[" then return nil end
  local inner = strsub(d, 2)
  if strsub(inner, 1, 1) == "L" then inner = strsub(inner, 2, -2) end
  return R.classOf(inner)
end

-- java.lang.reflect.Array.newInstance(componentType, n)
function R.classNewArray(c, n)
  local d = c.__desc
  local base, dims
  if strsub(d, 1, 1) == "[" then
    base, dims = parseArrayDesc(d)
    dims = dims + 1
  else
    base, dims = d, 1
  end
  local zero = nil
  if dims == 1 then zero = zeros[base] end
  return R.newArray(R.arrayType(base, dims, zero), n)
end

function R.classIsInstance(c, x)
  local d = c.__desc
  if d == "java.lang.Object" then return x ~= nil end
  return R.isInstance(x, d)
end
function R.classIsAssignableFrom(a, b)
  if a == b or a.__desc == "java.lang.Object" then return true end
  local P = classesByName[b.__desc]
  return P ~= nil and P.__anc[a.__desc] == true
end

return R
