package net.shift
package demo

import scala.concurrent.ExecutionContext.global
import org.apache.log4j.BasicConfigurator
import common.Config
import engine.ShiftApplication
import engine.ShiftApplication._
import engine.http._
import engine.http.Response._
import net.shift.common.Path
import net.shift.common.State
import net.shift.engine.utils.ShiftUtils
import net.shift.loc.Language
import net.shift.security.Credentials
import net.shift.security.User
import template._
import net.shift.engine.page.Html5
import net.shift.security.BasicCredentials
import net.shift.security.Permission
import net.shift.io.IO
import net.shift.io.FileOps
import net.shift.io.IODefaults
import net.shift.spray.SprayServer

import Template._

object Main extends App with HttpPredicates with ShiftUtils with IODefaults {
  println("Starting Demo server")

  BasicConfigurator.configure

  def abcService(resp: AsyncResponse) {
    resp(TextResponse("abc service executed."))
  }

  def yzService(resp: AsyncResponse) {
    resp(TextResponse("yz service executed."))
  }
  def notFoundService(resp: AsyncResponse) {
    resp(TextResponse("Sorry ... service not found"))
  }

  def serveService(resp: AsyncResponse) {
    resp(TextResponse("serve invoked"))
  }

  implicit val cfg = new Config

  import scala.concurrent.ExecutionContext.Implicits.global

  SprayServer.start(8080, new ShiftApplication {

    import Request._

    implicit val cfg = new Config

    // If we have a GET request and the path is /a/b/c
    val r1 = for {
      r <- GET
      _ <- path("a/b/c")
    } yield {
      println(r.param("cat"))
      service(abcService)
    }
    // Serve /page/first page
    val r2 = for {
      _ <- path("page/first")
      r <- withLanguage(Language("ro"))
      user <- authenticate("Login failed")
    } yield {
      println("Page first")
      Html5.pageFromFile(PageState(r, r.language, Some(user)), Path("pages/first.html"), FirstPage).map {
        _ withResponse (_ securityCookies user)
      }
    }

    // Serve /?/y/z where first part can be anything
    val r3 = for {
      _ <- tailPath
      _ <- path("y/z")
    } yield service(yzService)

    // Serve ?/1/?/?/3 the first and the two parts in the middle can be anything
    val r4 = for {
      Path(_, "1" :: a :: b :: "3" :: Nil) <- tailPath
      r <- req
    } yield service(serveService)

    val r0 = for {
      r <- path("")
      u <- user
    } yield Html5.pageFromFile(PageState(r, r.language, u), Path("pages/first.html"), FirstPage)

    val multi = for {
      _ <- path("form")
      r <- req
      mp <- multipartForm
    } yield {
      for { p <- mp.parts } yield {
        p match {
          case b @ BinaryPart(h, content) =>
            for {
              ContentDisposition(value, params) <- h.get("Content-Disposition")
            } yield {
              IO.arrayProducer(content)(FileOps writer Path(params.get("filename")))
            }
          case t => println(t)
        }
      }
      Html5.pageFromFile(PageState(r, r.language), Path("pages/first.html"), FirstPage)
    }

    val admin = for {
      _ <- path("admin")
      u <- user
    } yield {
      println("Got user " + u)
      service(_(TextResponse("admin")))
    }

    implicit def login(creds: Credentials): Option[User] = {
      creds match {
        case BasicCredentials("marius", "boot") => Some(User("marius", None, Set(Permission("read"), Permission("write"))))
        case BasicCredentials("ali", "boot")    => Some(User("ali", None, Set(Permission("read"))))
        case c =>
          println(c)
          None
      }
    }

    def servingRule = staticFiles(Path("web")) |
      r0 |
      r1 |
      r2 |
      r3 |
      multi |
      admin |
      r4 |
      service(notFoundService)
  })

}
