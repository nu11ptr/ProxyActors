/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api.actor.examples

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}
// NOTE: This is not used by the actors at all - just to handle the 'onComplete'
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import api.actor._

class Pong {
  def printPong() { Thread.sleep(2000); println("Pong! (no wait)") }

  def getPong = { Thread.sleep(1000); "Pong! (wait)" }

  def getFuturePong = {
    Thread.sleep(500)
    Promise.successful("Pong! (future)").future
  }
}

class Ping(val pong: Pong) {
  def printPing() { Thread.sleep(1000); println("Ping! (no wait)"); pong.printPong() }

  def getPing = { Thread.sleep(500); "Ping! (wait)\n" + pong.getPong }

  def getFuturePing = {
    Thread.sleep(1000)
    Promise.successful("Ping! (future)\n" +
      Await.result(pong.getFuturePong, Duration.Inf)).future
  }
}

object SimpleExample extends App {
  // We create each actor with its own single thread pool
  val pong = proxyActor[Pong](context = singleThreadContext)
  // We pass pong to ping in Ping's constructor
  val ping = proxyActor[Ping](args = List((pong, classOf[Pong])),
                              context = singleThreadContext)

  println("*** Test Starting ***")

  print("*** Calling wait ping...")
  println(ping.getPing)
  println("done.")

  print("*** Calling no wait ping...")
  ping.printPing()
  println("done.")

  print("*** Calling future ping...")
  ping.getFuturePing.onComplete {
    case Success(msg) => println(msg)
    case Failure(e)   => throw e
  }
  println("done.")

  println("*** Test Ending ***")
  actorFinished(ping)
  actorFinished(pong)
}