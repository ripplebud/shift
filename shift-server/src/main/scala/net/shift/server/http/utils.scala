package net.shift.server.http

import net.shift.loc.Language
import scala.util.Try
import net.shift.io.IO

object AcceptLanguage {

  def unapply(header: TextHeader): Option[List[Language]] = {
    header match {
      case TextHeader("Accept-Language", value) =>
        val langs = for {
          part <- value.split(",")
        } yield {
          val split = part.split(";")
          val l = if (split.length > 1) {
            val q = split(1).split("=")
            val quality = q(1).trim.toDouble
            (split(0), quality)
          } else {
            (split(0), 1.0)
          }
          val langParts = l._1.split("-")
          val lang = if (langParts.length > 1)
            Language(langParts(0).trim, Some(langParts(1).trim))
          else
            Language(langParts(0).trim)
          (lang, l._2)
        }

        Some(langs.sortWith { case ((l, q), (l1, q1)) => q >= q1 } map (_._1) toList)
      case _ => None
    }
  }
}

object HTTPUtils {

  def formURLEncodedToParams(req: Request): Try[List[Param]] = {
    IO.producerToString(req.body) flatMap { b => new HttpParser().parseParams(b) }
  }

  def formURLEncodedToParams(str: String): Try[List[Param]] = {
    new HttpParser().parseParams(str)
  }

}
