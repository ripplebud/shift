package net.shift
package http

import net.shift.io.IO._
import net.shift.common.Path
import net.shift.io.FileSystem
import net.shift.io.IO
import scala.util.Try
import net.shift.common.FileSplit
import net.shift.io.BinProducer

object Responses {

  def jsFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "text/javascript")
  def cssFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "text/css")
  def htmlFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "text/html")
  def jsonFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "text/json")
  def xmlFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "text/xml")
  def textFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "text/plain")
  def binFileResponse(path: Path)(implicit fs: FileSystem) = fileResponse(path, "application/octet-stream")
  def imageFileResponse(path: Path)(implicit fs: FileSystem) = {
    path.parts.last match {
      case FileSplit(name, "svg")  => fileResponse(path, "image/svg+xml")
      case FileSplit(name, "png")  => fileResponse(path, "image/png")
      case FileSplit(name, "jpg")  => fileResponse(path, "image/jpg")
      case FileSplit(name, "jpeg") => fileResponse(path, "image/jpeg")
      case FileSplit(name, "gif")  => fileResponse(path, "image/gif")
      case FileSplit(name, "tif")  => fileResponse(path, "image/tiff")
      case FileSplit(name, "tiff") => fileResponse(path, "image/tiff")
      case FileSplit(name, "bmp")  => fileResponse(path, "image/bmp")
      case FileSplit(name, "ico")  => fileResponse(path, "image/x-icon")

    }

  }

  def fileResponse(path: Path, mime: String)(implicit fs: FileSystem): Try[Response] = {
    for { (size, prod) <- IO.fileProducer(path) } yield {
      Response(code = 200,
        headers = List(TextHeader("Content-Type", mime),
          TextHeader("Content-Length", size.toString)),
        body = prod)
    }
  }

  def producerResponse(bdy: BinProducer, size: Long) = Response(code = 200,
    headers = List(TextHeader("Content-Length", size.toString)),
    body = bdy)

  def textResponse(text: String) = {
    val bd = Body(text)
    Response(code = 200,
      headers = List(
        Headers.contentType(ContentType.TextPlain),
        Headers.contentLength(bd.size)),
      body = bd)
  }

  def ok = Response(code = 200, body = Body.empty)

  def created = Response(code = 201, body = Body.empty)
  def accepted = Response(code = 201, body = Body.empty)

  def redirect(location: String) =
    Response(code = 302, headers = List(TextHeader("Location", location)), body = Body.empty)
    
  def notModified = Response(code = 304, body = Body.empty)

  def badRequest = Response(code = 400, body = Body.empty)

  def basicAuthRequired(msg: String, realm: String) =
    Response(code = 401, headers = List(TextHeader("WWW-Authenticate", s"""Basic realm="$realm"""")), body = Body(msg))

  def paymentRequired = Response(code = 402, body = Body.empty)
  def forbidden = Response(code = 403, body = Body.empty)
  def notFound = Response(code = 404, body = Body.empty)
  def confilct = Response(code = 409, body = Body.empty)

  def serverError = Response(code = 500, body = Body.empty)
  def notImplemented = Response(code = 501, body = Body.empty)
  def serviceUnavailable = Response(code = 503, body = Body.empty)

}
