/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/13/13
 * Time: 11:43 PM
 */

package api.actor.examples

import api.actor._
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

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

  @omit
  def printE() { println("This is method E.") }

  def returnF: Future[String] = {
    Thread.sleep(3000)
    Promise.successful("This is method F").future
  }
}

object SimpleTest extends App {
  println("*** Creating Proxy ***")
  val proxy = proxyActor[TestClass]()
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

  Thread.sleep(5000)
  shutdown()
}