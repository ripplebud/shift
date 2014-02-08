
package net.shift.template
package test

import net.shift.common._
import State._
import scala.xml._
import java.util.Locale
import net.shift.loc.Language
import scala.util.Success

object TemplateTest extends App with XmlUtils with Selectors {
  val page = <html>
               <head>
               </head>
               <body>
                 <FORM data-snip="form1" action="http://somesite.com/prog/adduser" method="post">
                   <P/>
                   <LABEL for="firstname">First name: </LABEL>
                   <INPUT type="text" id="firstname"/><BR/>
                   <LABEL for="lastname">Last name: </LABEL>
                   <INPUT type="text" id="lastname"/><BR/>
                   <LABEL for="email">email: </LABEL>
                   <INPUT data-snip="email" type="text"/><BR/>
                   <INPUT type="radio" name="sex" value="Male"/>
                   Male<BR/>
                   <INPUT type="radio" name="sex" value="Female"/>
                   Female<BR/>
                   <INPUT type="submit" value="Send"/>
                   <INPUT type="reset"/>
                   <P/>
                 </FORM>
               </body>
             </html>

  import Snippet._
  val snippets = new DynamicContent[String] {
    def snippets = List(
      snip[String]("form1") {
        s =>
          Console println s.state
          val SnipNode(name, attrs, childs) = s.node
          Success(("form", <form>{ childs }</form>))
      },
      snip[String]("email") {
        s =>
          Console println s.state
          Success(("email", <input type="text" id="email1">Type email here</input>))
      })
  }

  implicit val sel = bySnippetAttr[SnipState[String]]
  val res = Template[String](snippets)

  val e = for {
    t <- res.run(page)
  } yield {
    mkString(t)
  }
  for {
    c <- e(SnipState("start", Language("ro"), NodeSeq.Empty))
  } yield {
    Console println c._2
  }
}