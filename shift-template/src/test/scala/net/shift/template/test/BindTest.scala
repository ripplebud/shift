package net.shift
package template
package test

import Binds._

object BindTest extends App {
  val xml = <div class="images">
              <ul>
                <f:li>
                  <f:img class="thumb"/>
                </f:li>
              </ul>
            </div>

  val images = List("a", "b", "c")

  val res = bind(xml) {
    case "ul" > (_ / childs) => <ul>{ childs }</ul>
    case "f:li" > (_ / childs) => childs
    case "f:img" > (a / _) =>
      images map { i =>
        <li>
          { <img src={ i }></img> % a }
        </li>
      }
  }

  println(res)

}