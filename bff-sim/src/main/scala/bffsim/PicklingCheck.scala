package bffsim

import java.nio.ByteBuffer

import boopickle.Default.*
import communication.BFFPicklers.*
import models.bff.ingame.InGameWSProtocol

/** Round-trips in-game websocket messages through boopickle, like the client and the server do. */
object PicklingCheck {

  final case class Report(messages: Int, bytes: Long, checksum: Int, failures: Int)

  def encode(message: InGameWSProtocol): Array[Byte] = {
    val buffer = Pickle.intoBytes[InGameWSProtocol](message)
    val array  = new Array[Byte](buffer.remaining())
    buffer.get(array)
    array
  }

  def decode(bytes: Array[Byte]): InGameWSProtocol =
    Unpickle[InGameWSProtocol].fromBytes(ByteBuffer.wrap(bytes))

  /** FNV-1a over the bytes, to compare the encodings of two platforms. */
  def fnv(hash: Int, bytes: Array[Byte]): Int = {
    var h = hash
    var i = 0
    while (i < bytes.length) {
      h = (h ^ (bytes(i) & 0xff)) * 16777619
      i += 1
    }
    h
  }

  def hex(bytes: Array[Byte]): String = {
    val digits = "0123456789abcdef"
    val sb     = new StringBuilder
    for (b <- bytes) sb.append(digits.charAt((b >> 4) & 0xf)).append(digits.charAt(b & 0xf))
    sb.toString
  }

  def check(messages: Iterable[InGameWSProtocol], log: String => Unit): Report = {
    var count    = 0
    var total    = 0L
    var checksum = 0x811c9dc5
    var failures = 0
    for (message <- messages) {
      val bytes = encode(message)
      count += 1
      total += bytes.length
      checksum = fnv(checksum, bytes)
      val decoded =
        try decode(bytes)
        catch { case e: Throwable => e }
      if (decoded != message) {
        failures += 1
        if (failures <= 3) log(s"MISMATCH: $message\n  decoded: $decoded\n  bytes: ${hex(bytes)}")
      }
    }
    Report(count, total, checksum, failures)
  }
}
