/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api.actor

import scala.concurrent.{Await, Promise, Future, ExecutionContextExecutorService}
import scala.concurrent.duration.Duration
import java.util.Collection
import java.util.concurrent.{TimeUnit, Callable}
import org.scalatest.FunSuite
import org.scalamock.scalatest.MockFactory

private class ConstructorTest(i: Int) { def this(s: String) = this(s.toInt) }

private class UnitTestClass {
  @volatile var called: Boolean = false

  def doNoWait() { called = true }
  def doWait: Boolean = true
  def doFuture: Future[Boolean] = Promise.successful(true).future
}

class ProxyActorTests extends FunSuite with MockFactory {
  // NOTE: Hack! ScalaMock can't mock ExecutionContextExecutorService directly
  // without implementing all overloaded methods (overloaded method errors)
  private trait ECES extends ExecutionContextExecutorService {
    def invokeAny[T](tasks: Collection[_ <: Callable[T]]) = ???
    def invokeAny[T](tasks: Collection[_ <: Callable[T]], timeout: Long,
                     unit: TimeUnit) = ???
    def invokeAll[T](tasks: Collection[_ <: Callable[T]]) = ???
    def invokeAll[T](tasks: Collection[_ <: Callable[T]], timeout: Long,
                     unit: TimeUnit) = ???
    def submit[T](task: Runnable, result: T) = ???
    def submit(task: Runnable) = ???
    def submit[T](task: Callable[T]) = ???
  }

  test("Proxy actor: no wait methods") {
    val actor = proxyActor[UnitTestClass](context = singleThreadContext)
    actor.doNoWait()
    expectResult(true)(actor.called)
    actorsFinished(actor)
  }

  test("Proxy actor: wait methods") {
    val actor = proxyActor[UnitTestClass](context = singleThreadContext)
    expectResult(true)(actor.doWait)
    actorsFinished(actor)
  }

  test("Proxy actor: future methods") {
    val actor = proxyActor[UnitTestClass](context = singleThreadContext)
    expectResult(true)(Await.result(actor.doFuture, Duration.Inf))
    actorsFinished(actor)
  }

  test("Proxy actor: multiple constructor") {
    intercept[IllegalArgumentException] {
      proxyActor[ConstructorTest](args = Seq(this, 1))
    }

    proxyActor[ConstructorTest](args = Seq(1), types = Seq(classOf[Int]))
    proxyActor[ConstructorTest](args = Seq("1"), types = Seq(classOf[String]))
  }

  test("Proxy actor: implements ActorSupport") {
    proxyActor[AnyRef]().asInstanceOf[ActorSupport]
  }

  test("Proxy actors: quantity is correct") {
    expectResult(3)(proxyActors[AnyRef](3).size)
    expectResult(3)(sameThreadContext.proxyActors[AnyRef](3).size)
  }

  test("Actors finished: thread pool is shutdown - varargs") {
    val ec = mock[ECES]
    val context = actorContext(ec)
    val actor1 = context.proxyActor[AnyRef]()
    val actor2 = context.proxyActor[AnyRef]()

    (ec.shutdown _).expects()
    (ec.awaitTermination _).expects(Long.MaxValue, TimeUnit.DAYS)
    actorsFinished(actor1, actor2)
  }

  test("Actors finished: thread pool is not shutdown if not all finished - varargs") {
    val ec = mock[ECES]
    val context = actorContext(ec)
    val actor1 = context.proxyActor[AnyRef]()
    val actor2 = context.proxyActor[AnyRef]()

    (ec.shutdown _).expects().never
    (ec.awaitTermination _).expects(Long.MaxValue, TimeUnit.DAYS).never
    actorsFinished(actor1)
  }

  test("Actors finished: thread pool shutdown - list") {
    val ec = mock[ECES]
    val context = actorContext(ec)
    val actors = context.proxyActors[AnyRef](2)

    (ec.shutdown _).expects()
    (ec.awaitTermination _).expects(Long.MaxValue, TimeUnit.DAYS)
    actorsFinished(actors)
  }
}