package sangria.util

import java.io.{ByteArrayOutputStream, PrintStream}

import org.scalatest.matchers.should.Matchers

trait OutputMatchers extends Matchers {
  def captureStdErr(fn: => Unit) = {
    val output = new ByteArrayOutputStream()
    val printStream = new PrintStream(output)
    val oldErr = System.err

    try {
      System.setErr(printStream)
      fn
    } finally {
      System.setErr(oldErr)
      printStream.flush()
      printStream.close()
    }

    output.toString("UTF-8")
  }

  def captureConsoleOut(fn: => Unit) = {
    val output = new ByteArrayOutputStream()

    Console.withOut(output) {
      fn
    }

    output.toString("UTF-8")
  }
}
