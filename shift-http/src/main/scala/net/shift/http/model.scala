package net.shift.http

import java.nio.ByteBuffer
import scala.util.Try
import net.shift.io._
import net.shift.loc.Language

trait Payload

case class HTTPParam(name: String, value: List[String])
object HTTPVer {
  val Ver_1_1 = HTTPVer(1 toByte, 1 toByte)
}
case class HTTPVer(major: Byte, minor: Byte)

sealed trait HeaderItem {
  def name: String
  def headerLine: String
}

case class TextHeader(name: String, value: String) extends HeaderItem {
  def headerLine = s"$name: $value"
}

case class Cookie(cookieName: String, cookieValue: String) extends HeaderItem {
  def name = "Cookie"
  def headerLine: String = s"$name: $cookieName=cookieValue"
}

object SetCookie {
  def apply(name: String, value: String) = new SetCookie(name, value, None, None, None, None, false, false)
  def apply(name: String, value: String, path: String) = new SetCookie(name, value, None, Some(path), None, None, false, false)
}
case class SetCookie(cookieName: String,
                     cookieValue: String,
                     domain: Option[String],
                     path: Option[String],
                     maxAge: Option[Long],
                     version: Option[Int],
                     secure: Boolean,
                     httpOnly: Boolean) extends HeaderItem {

  def name = "Set-Cookie"

  def setCookieName(value: String) = this.copy(cookieName = value)
  def setCookieValue(value: String) = this.copy(cookieValue = value)
  def setDomain(value: String) = this.copy(domain = Some(value))
  def setPath(value: String) = this.copy(path = Some(value))
  def setMaxAge(value: Long) = this.copy(maxAge = Some(value))
  def setVersion(value: Int) = this.copy(version = Some(value))
  def setSecure(value: Boolean) = this.copy(secure = value)
  def setHttpOnly(value: Boolean) = this.copy(httpOnly = value)

  def headerLine: String = s"$name: ${cookieName}=${cookieValue}" +
    domain.map(d => s";Domain=$d").getOrElse("") +
    maxAge.map(d => s";Max-Age=$d").getOrElse("") +
    path.map(d => s";Path=$d").getOrElse("") +
    (if (secure) ";Secure" else "") +
    version.map(d => s";Version=$d").getOrElse("")
}

object HTTPBody {
  def apply(body: String) = new HTTPBody(List(ByteBuffer.wrap(body.getBytes("UTF-8"))))
  def empty = HTTPBody(Nil)
}
case class HTTPBody(parts: Seq[ByteBuffer]) extends BinProducer {
  def size = parts.map { _.limit }.sum

  def apply[O](it: Iteratee[ByteBuffer, O]): Iteratee[ByteBuffer, O] = {
    val data = (parts map { d => Data(d) }) ++ List(EOF)
    (it /: data) {
      case (Cont(f), e) => f(e)
      case (r, _)       => r
    }
  }
}

object HTTPUri {
  def apply(path: String) = new HTTPUri(None, None, path, Nil)
}
case class HTTPUri(host: Option[String], port: Option[Int], path: String, params: List[HTTPParam]) {
  def param(name: String) = params find { _.name == name }
  def paramValue(name: String) = param(name) map { _.value }
}

case class HTTPRequest(
    method: String,
    uri: HTTPUri,
    version: HTTPVer,
    headers: Seq[HeaderItem],
    body: BinProducer) extends Payload {

  lazy val languages: Seq[Language] = {
    def langFromString(str: String) = str.split("-").toList match {
      case lang :: country :: _ => Language(lang, Some(country))
      case lang :: Nil          => Language(lang)
      case _                    => Language("en")
    }

    stringHeader("Accept-Language") match {
      case Some(value) =>
        val lngs = for { item <- value.split(",") } yield {
          item.split(";").toList match {
            case lng :: q :: Nil =>
              val qarr = q.split("=")

              val quality = if (qarr.length == 2) {
                qarr(1).trim.toDouble
              } else {
                0.0
              }

              (langFromString(lng.trim), quality)
            case lng :: Nil => (langFromString(lng.trim), 1.0)
            case _          => (Language("en"), 0.1)
          }
        }
        lngs.sortWith { case ((l1, q1), (l2, q2)) => q1 >= q2 } map { _._1 }

      case _ => List(Language("en"))
    }
  }

  lazy val language: Language = languages head

  def header(name: String): Option[HeaderItem] = headers find { _.name == name }

  def stringHeader(name: String): Option[String] = header(name) match {
    case Some(TextHeader(name, value)) => Some(value.trim)
    case _                             => None
  }

  def longHeader(name: String): Option[Long] = header(name) match {
    case Some(TextHeader(name, value)) => Try(value.trim.toLong).toOption
    case _                             => None
  }

  def intHeader(name: String): Option[Int] = header(name) match {
    case Some(TextHeader(name, value)) => Try(value.trim.toInt).toOption
    case _                             => None
  }

  def booleanHeader(name: String): Option[Boolean] = header(name) match {
    case Some(TextHeader(name, value)) => Try(value.trim.toBoolean).toOption
    case _                             => None
  }

  def doubleHeader(name: String): Option[Double] = header(name) match {
    case Some(TextHeader(name, value)) => Try(value.trim.toDouble).toOption
    case _                             => None
  }

  lazy val cookies = headers flatMap {
    case c: Cookie => List(c)
    case _         => Nil
  }

  def cookie(name: String) = cookies find (_.cookieName == name)
}

case class HTTPResponse(code: Int,
                        reason: String = "OK",
                        headers: List[HeaderItem] = Nil,
                        body: BinProducer) extends Payload {

  def asBinProducer: BinProducer = new BinProducer {

    var sendOnlyBody: Boolean = false;

    def headerBuffer = {

      val extra = if (body == HTTPBody.empty) {
        List(TextHeader("Content-Length", "0"))
      } else
        Nil

      val headersStr = (headers ++ extra) map {
        case h => s"${h.headerLine}\r\n"
      } mkString

      val header = s"HTTP/1.1 $code $reason\r\n$headersStr\r\n"

      Data(ByteBuffer.wrap(header.getBytes("UTF-8")))
    }

    def apply[O](it: Iteratee[ByteBuffer, O]): Iteratee[ByteBuffer, O] = {

      if (sendOnlyBody) {
        val r = body(it)
        sendOnlyBody = false;
        r
      } else {
        val next = it match {
          case Cont(f) => f(headerBuffer)
          case r       => r
        }

        next match {
          case Cont(_)      => body(next)
          case e @ Error(t) => e
          case r =>
            sendOnlyBody = true
            r
        }
      }
    }
  }
}
