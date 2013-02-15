/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/13/13
 * Time: 11:43 PM
 */

package api.actor.examples

import api.actor.ProxyActor
import api.actor.ProxyActor._

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
}

object SimpleTest extends App {
  println("*** Creating Proxy ***")
  val proxy = ProxyActor(classOf[TestClass])
  println("*** Test Starting ***")
  println(proxy.returnD)
  proxy.printB()
  proxy.printC()
  println("*** Test Ending ***")

  Thread.sleep(1000)
  ProxyActor.DefaultExecutor.shutdown()
}