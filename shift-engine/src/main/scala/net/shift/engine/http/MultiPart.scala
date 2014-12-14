package net.shift
package engine.http

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.TailCalls._
import scala.util.parsing.combinator._
import scala.util.parsing.input.Position
import scala.util.parsing.input.Reader
import net.shift.common.Log
import net.shift.common.TimeUtils
import scalax.io.Input
import net.shift.engine.ShiftFailure

sealed trait MultiPart

case class MultiPartBody(parts: List[MultiPart]) extends MultiPart

case class BodyPart(headers: Map[String, Header], body: MultiPart) extends MultiPart

case class BinaryPart(headers: Map[String, Header], content: Array[Byte]) extends MultiPart

case class TextPart(headers: Map[String, Header], content: String) extends MultiPart

object MultipartParser {
  def apply(boundry: String) = new MultipartParser(boundry.getBytes("UTF-8"))
}

class MultipartParser(boundary: Array[Byte]) extends Parsers with TimeUtils with Log {
  type Elem = Byte

  def loggerName = "MultipartParser"

  val bound = "--".getBytes("UTF-8") ++ boundary

  implicit def char2Parser(ch: Char): Parser[Byte] = Parser { in =>
    if (ch.toByte == in.first) Success(in.first, in.rest) else Failure("Char mismatch", in)
  }

  implicit def array2Parser(buff: Array[Byte]): Parser[Array[Byte]] = Parser { in =>

    @tailrec def walk(arr: Array[Byte], in: Input): ParseResult[Array[Byte]] = {
      if (arr.isEmpty) {
        Success(arr, in)
      } else if (arr.head == in.first) {
        walk(arr.tail, in.rest)
      } else {
        Failure("No match", in)
      }
    }
    walk(buff, in)
  }

  def crlf = Array[Byte](13, 10)

  def parse(in: scalax.io.Input): Try[MultiPartBody] = parse(in.byteArray)

  def parse(in: Array[Byte]): Try[MultiPartBody] = duration {
    multiParser(BinReader(in, 0)) match {
      case Success(v, _) => scala.util.Success(v)
      case Failure(v, _) => scala.util.Failure(ShiftFailure[MultiPartBody](v))
      case Error(v, _)   => scala.util.Failure(ShiftFailure[MultiPartBody](v))
    }
  } { d => debug(s"Multipart parsing took: $d") }

  def key = rep1(acceptIf(b => (b >= 'a' && b <= 'z') ||
    (b >= 'A' && b <= 'Z') ||
    (b >= '0' && b <= '9') || b == '_' || b == '-')(err => "Not a key character " + err)) ^^ { r => r.toArray }

  def value = rep1(acceptIf(b => (b >= 'a' && b <= 'z') ||
    (b >= 'A' && b <= 'Z') ||
    (b >= '0' && b <= '9') || b == '_' || b == '-' || b == '/')(err => "Not a value character " + err)) ^^ { r => r.toArray }

  def stringLiteral = ('\"' ~> rep(acceptIf(b => b != '\"')(err => "Not a stringlit char " + err)) <~ '\"') ^^ { r => r.toArray }

  def multiParser: Parser[MultiPartBody] = bound ~> rep((headers ~ (crlf ~> partParser)) ^^ {
    case k ~ v =>
      k.get("Content-Type") match {
        case Some(Header(_, value, _)) if (value.startsWith("text")) => TextPart(k, new String(v))
        case Some(_) => BinaryPart(k, v)
        case _ => TextPart(k, new String(v))
      }
  }) ^^ { l => MultiPartBody(l) }

  def noCRLFSpace = accept(' '.toByte) | accept('\t'.toByte)

  def ws(t: String) = rep(noCRLFSpace) ~> t.getBytes("UTF-8") <~ rep(noCRLFSpace)

  def param = (key ~ opt(ws("=") ~> (value | stringLiteral))) ^^ {
    case k ~ Some(v) => (k, v)
    case k ~ None    => (k, k)
  }

  def headers = (rep(header) <~ crlf) ^^ { h => h.map(e => (e.key, e)).toMap }

  def header = (crlf ~> ((key <~ ws(":")) ~ value)) ~ rep(ws(";") ~> param) ^^ {
    case k ~ v ~ p => Header(new String(k), new String(v), Map(p.map { case (k, v) => (new String(k), new String(v)) }: _*))
  }

  def partParser: Parser[Array[Byte]] = Parser { in =>

    val sepBound = (Array[Byte](13, 10) ++ bound) toList

    @tailrec def continue(in: Input, end: List[Byte], res: ListBuffer[Byte]): ParseResult[ListBuffer[Byte]] = {

      if (end.isEmpty) {
        Success(res.dropRight(sepBound.size), in)
      } else if (in.atEnd) {
        Failure(s"Suffix $bound not found", in)
      } else if (in.first != end.head) {
        continue(in.rest, sepBound, res += in.first)
      } else {
        continue(in.rest, end.tail, res += in.first)
      }
    }
    duration {
      continue(in, sepBound, ListBuffer[Byte]()).map(_ toArray)
    } { d => debug(s"Parsing binary part took: $d") }
  }

}

object BinReader {
  def apply(in: scalax.io.Input) = new BinReader(in.byteArray, 0)
}

case class BinReader(in: Array[Byte], position: Int) extends Reader[Byte] {

  def first = in(position)

  def rest = new BinReader(in, position + 1)

  def pos: Position = new Position {
    def line = 0
    def column = position
    def lineContents: String = ""
  }

  def atEnd: Boolean = position >= in.length
}