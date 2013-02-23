/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api.actor.examples

import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}
import api.actor._

class TestClass {
  println("*** Constructor Starting ***")
  printA()
  printB()
  printC()
  println("*** Constructor Ending ***")

  private def printA() { println("This is (private) method A") }

  protected[examples] def printB() { println("This is (protected) method B") }

  def printC() {
    println("This is method C. Calling method A and B!")
    printA()
    printB()
  }

  def returnD = "This is method D."

  def printE() { println("This is method E.") }

  def returnF: Future[String] = {
    Thread.sleep(3000)
    Promise.successful("This is method F").future
  }
}

object SimpleTest extends App {
  println("*** Creating Proxy ***")
  val proxy = proxyActor[TestClass](context = singleThreadContext)
  println("*** Test Starting ***")
  println(proxy.returnD)
  proxy.printB()
  proxy.printC()
  proxy.printE()

  import scala.concurrent.ExecutionContext.Implicits.global
  proxy.returnF.onComplete {
    case Success(msg) => println(msg)
    case Failure(e)   => e.printStackTrace()
  }

  println("*** Test Ending ***")

  actorFinished(proxy)
}