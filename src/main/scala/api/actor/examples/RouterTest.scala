/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/20/13
 * Time: 10:09 PM
 */

package api.actor.examples

import api.actor._
import scala.annotation.tailrec

object RouterTest extends App {
  trait Test {
    def doWork()
  }

  class Tester extends Test {
    var counter = 0

    def doWork() {
      def fib(n: Int): Int = {
        @tailrec
        def _fib(n: Int, b: Int, a: Int): Int = n match {
          case 0 => a
          case _ => _fib(n - 1, a + b, b)
        }

        _fib(n, 1, 0)
      }

      fib(100000000)
      counter += 1
    }
  }

  val context = allCoresContext
  val actors = context.proxyActors[Tester](Runtime.getRuntime.availableProcessors)
  val router = proxyRouter[Test](actors)

  for (_ <- 1 to 100) router.doWork()

  actors.foreach { a =>
    val count = a.counter
    println(s"I worked $count time(s)")
  }

  actorsFinished(actors)
}