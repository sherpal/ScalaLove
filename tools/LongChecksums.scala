//> using scala 3.8.4
//> using file ../selftest/src/main/scala/selftest/LongChecks.scala

// Prints the JVM's checksums of the self-test's Long checks, for `LongChecks.expected`.
// Usage: scala-cli run tools/LongChecksums.scala

@main def longChecksums(): Unit =
  for ((name, checksum) <- selftest.LongChecks.checksums)
    println(s"    \"$name\" -> ${checksum()},")
