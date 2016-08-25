package net.shift.common

import scala.util.parsing.combinator.Parsers
import scala.annotation.tailrec
import java.nio.ByteBuffer
import scala.collection.mutable.ListBuffer
import net.shift.io.BinProducer
import scala.util.parsing.input.Reader
import scala.util.parsing.input.Position
import net.shift.io.IO
import java.io.IOException

trait ShiftParsers extends Parsers {
  type Elem = Byte

  implicit def charToByte(c: Char): Byte = c.toByte
  implicit def charToBytes(c: Char): List[Byte] = List(c.toByte)

  val reserved = Set(';', '/', '?', ':', '@', '&', '=', '+', '$', ',', '\r', '\n', ' ')

  def capitals: Parser[String] = rep1(acceptIf(b =>
    b >= 'A' && b <= 'Z')(err => "Not a capital character " + err)) ^^ { _ map { _ toChar } mkString }

  def digit: Parser[Byte] = acceptIf(b => b >= '0' && b <= '9')(err => "Not a digit character " + err) ^^ { b => (b - 48).toByte }

  def int: Parser[Int] = rep1(digit) ^^ { _.mkString.toInt }

  def noCRLFSpace: Parser[Byte] = (accept(' ') | accept('\t')) ^^ { _ head }

  def crlf: Parser[Unit] = accept('\r') ~> accept('\n') ^^ { b => () }

  def ws = rep(noCRLFSpace)

  def str(s: String): Parser[String] = (ws ~> acceptSeq(s.getBytes("UTF-8")) <~ ws) ^^ { case b => new String(b.toArray, "UTF-8") }

  def chr(c: Char): Parser[Byte] = accept(c)

  def notReserved(allow: Char*): Parser[String] = rep1(acceptIf {
    case b =>
      val res = reserved contains b.toChar
      !res || (res && allow.contains(b))
  }(err => "Not a reserved character " + err)) ^^ {
    _ map { _ toChar } mkString
  }

  def uriValid: Parser[String] = rep1(acceptIf { b =>
    b != ' ' &&
      b != '\t' &&
      b != ':' &&
      b != '?' &&
      b != '/'
  }(err => "Not a uri character " + err)) ^^ { _ map { _ toChar } mkString }

  def atEnd: Parser[Unit] = new Parser[Unit] {
    def apply(in: Input): ParseResult[Unit] = {
      if (in.atEnd)
        Success((), in)
      else
        Failure("Not at end", in)
    }
  }

  def until[T](p: Parser[T], retryPInput: Boolean): Parser[ByteBuffer] = new Parser[ByteBuffer] {
    def apply(in: Input): ParseResult[ByteBuffer] = {

      @tailrec
      def walk(i: Input, acc: ListBuffer[Byte]): (Input, ListBuffer[Byte]) = {
        val inBeforeP = i
        val r = p(i)

        if (!i.atEnd && !r.successful) {
          walk(i.rest, acc += i.first)
        } else {
          if (retryPInput)
            (inBeforeP, acc)
          else
            (r.next, acc)
        }
      }

      val (i, res) = walk(in, new ListBuffer())

      if (i.atEnd) {
        if (res.isEmpty)
          Failure("No content found", i)
        else
          Success(ByteBuffer.wrap(res.toArray), i)
      } else
        Success(ByteBuffer.wrap(res.toArray), i)

    }
  }
}

object BinReader {
  import IO._
  def apply(in: BinProducer) = chunks(in) map { arr => new BinReader(arr, 0) }
}

case class BinReader(in: Seq[ByteBuffer], position: Int = 0) extends Reader[Byte] {

  lazy val size = in.map { _.limit }.sum

  lazy val first = {
    if (!in.isEmpty && in.head.hasRemaining()) {
      in.head.get
    } else {
      throw new IOException("Not enough data")
    }
  }

  def rest = {
    if (!in.isEmpty) {
      if (in.head.hasRemaining()) {
        BinReader(in, position + 1)
      } else {
        BinReader(in.tail, position + 1)
      }
    } else {
      throw new IOException("Not enough data")
    }
  }

  def pos: Position = new Position {
    def line = 0
    def column = position
    def lineContents: String = ""
  }

  def atEnd: Boolean = position >= size
}