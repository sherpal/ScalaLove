package selftest

import java.lang.{Double as JDouble, Float as JFloat, Long as JLong}

/** Long (and floating point bits) operations on edge cases and pseudo-random operands, each folded
  * into a checksum. `expected` holds the JVM's checksums, printed by
  * `scala-cli run tools/LongChecksums.scala`.
  */
object LongChecks {
  private val edges: List[Long] = List(
    0L, 1L, -1L, 2L, -2L, 3L, 7L, 10L, -10L, 1000000000L, 4294967295L, 4294967296L, -4294967296L,
    Int.MaxValue.toLong, Int.MinValue.toLong, (1L << 52) + 1, 1L << 53, -(1L << 53) - 1, 1L << 62,
    Long.MaxValue, Long.MinValue, Long.MinValue + 1, 0x123456789abcdef0L, -0x123456789abcdef0L
  )

  /** Of all magnitudes, from a xorshift on Ints (not to depend on Longs). */
  private def randoms(n: Int): List[Long] = {
    var seed = 0x2545f491
    def next(): Int = {
      seed ^= seed << 13
      seed ^= seed >>> 17
      seed ^= seed << 5
      seed
    }
    List.fill(n) {
      val v = (next().toLong << 32) | (next().toLong & 0xffffffffL)
      v >> (next() & 63)
    }
  }

  val values: List[Long] = edges ++ randoms(40)

  private val doubles: List[Double] =
    values.map(_.toDouble) ++ values.map(_.toDouble / 3) ++ List(
      0.0, -0.0, 0.1, 1.5, 2.5, -2.5, 1e300, -1e300, 4.9e-324, 1e-310, 3.4e38, 3.5e38, 1.4e-45,
      1e-40, 9.223372036854776e18, -9.223372036854776e18, Double.NaN, Double.PositiveInfinity,
      Double.NegativeInfinity
    )

  private lazy val pairs = for (a <- values; b <- values) yield (a, b)

  private def fold[A](xs: List[A])(f: A => Int): Int = xs.foldLeft(0)((acc, x) => acc * 31 + f(x))

  private def binary(f: (Long, Long) => Long): Int = fold(pairs)((a, b) => f(a, b).hashCode)

  private def unary(f: Long => Long): Int = fold(values)(v => f(v).hashCode)

  private def flag(b: Boolean, bit: Int): Int = if (b) bit else 0

  /** The checks, by name: computed lazily, so that one failing does not stop the others. */
  val checksums: List[(String, () => Int)] = List(
    "+" -> (() => binary(_ + _)),
    "-" -> (() => binary(_ - _)),
    "*" -> (() => binary(_ * _)),
    "/" -> (() => binary((a, b) => if (b == 0) 0 else a / b)),
    "%" -> (() => binary((a, b) => if (b == 0) 0 else a % b)),
    "divideUnsigned" -> (() => binary((a, b) => if (b == 0) 0 else JLong.divideUnsigned(a, b))),
    "remainderUnsigned" -> (() =>
      binary((a, b) => if (b == 0) 0 else JLong.remainderUnsigned(a, b))
    ),
    "&" -> (() => binary(_ & _)),
    "|" -> (() => binary(_ | _)),
    "^" -> (() => binary(_ ^ _)),
    "<<" -> (() => binary((a, b) => a << b.toInt)),
    ">>>" -> (() => binary((a, b) => a >>> b.toInt)),
    ">>" -> (() => binary((a, b) => a >> b.toInt)),
    "comparisons" -> (() =>
      fold(pairs)((a, b) =>
        flag(a < b, 1) + flag(a <= b, 2) + flag(a > b, 4) + flag(a >= b, 8) + flag(a == b, 16) +
          flag(a != b, 32)
      )
    ),
    "compareUnsigned" -> (() => fold(pairs)((a, b) => JLong.compareUnsigned(a, b).sign)),
    "unary -" -> (() => unary(v => -v)),
    "toInt" -> (() => fold(values)(_.toInt)),
    "toDouble" -> (() => fold(values)(v => JDouble.doubleToLongBits(v.toDouble).hashCode)),
    "toString" -> (() => fold(values)(_.toString.hashCode)),
    "parseLong" -> (() => unary(v => JLong.parseLong(v.toString))),
    "toHexString" -> (() => fold(values)(v => JLong.toHexString(v).hashCode)),
    "toUnsignedString" -> (() => fold(values)(v => JLong.toUnsignedString(v).hashCode)),
    "numberOfLeadingZeros" -> (() => fold(values)(JLong.numberOfLeadingZeros)),
    "numberOfTrailingZeros" -> (() => fold(values)(JLong.numberOfTrailingZeros)),
    "bitCount" -> (() => fold(values)(JLong.bitCount)),
    "Double.toLong" -> (() => fold(doubles)(_.toLong.hashCode)),
    "doubleToLongBits" -> (() => fold(doubles)(d => JDouble.doubleToLongBits(d).hashCode)),
    "longBitsToDouble" -> (() =>
      unary(v => JDouble.doubleToLongBits(JDouble.longBitsToDouble(v)))
    ),
    "Double.toFloat" -> (() => fold(doubles)(d => JFloat.floatToIntBits(d.toFloat))),
    "intBitsToFloat" -> (() =>
      fold(values)(v => JFloat.floatToIntBits(JFloat.intBitsToFloat(v.toInt)))
    ),
    "Float arithmetic" -> (() =>
      fold(doubles.zip(doubles.reverse)) { (a, b) =>
        JFloat.floatToIntBits(a.toFloat * b.toFloat + a.toFloat / b.toFloat)
      }
    )
  )

  val expected: Map[String, Int] = Map(
    "+" -> -957253980,
    "-" -> -440733482,
    "*" -> 312254124,
    "/" -> 1732719209,
    "%" -> -1279802019,
    "divideUnsigned" -> 889585497,
    "remainderUnsigned" -> 543927745,
    "&" -> -1089873050,
    "|" -> -586517326,
    "^" -> -1515457396,
    "<<" -> 796821439,
    ">>>" -> -775309633,
    ">>" -> 666445642,
    "comparisons" -> -763931761,
    "compareUnsigned" -> -1800433906,
    "unary -" -> -1626535986,
    "toInt" -> -2107799608,
    "toDouble" -> -262600700,
    "toString" -> 1933327698,
    "parseLong" -> -1589690214,
    "toHexString" -> -611962531,
    "toUnsignedString" -> 1515285894,
    "numberOfLeadingZeros" -> 1317654570,
    "numberOfTrailingZeros" -> 787847131,
    "bitCount" -> 436630378,
    "Double.toLong" -> 739710781,
    "doubleToLongBits" -> -720683139,
    "longBitsToDouble" -> -1594808511,
    "Double.toFloat" -> 1826526951,
    "intBitsToFloat" -> 1547971933,
    "Float arithmetic" -> 670473670
  )
}
