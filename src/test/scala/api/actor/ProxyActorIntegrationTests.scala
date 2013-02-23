/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api.actor

import scala.language.postfixOps
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.annotation.tailrec
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import java.util.concurrent.Executors
import org.scalatest._
import org.scalatest.concurrent.Timeouts
import org.scalatest.time.SpanSugar

case class ThreadVerifier(diffList:   List[Option[Boolean]],
                          origThread: Thread = Thread.currentThread) {
  require(diffList.nonEmpty)

  def verify() {
    diffList.head match {
      case Some(diff) =>
        assert(if (diff) origThread != Thread.currentThread
               else origThread == Thread.currentThread)
      case None       =>
    }
  }

  def pop: ThreadVerifier = copy(diffList.tail, Thread.currentThread)
}

class BasicTestClass {
  val noWaitInt = new AtomicInteger(0)

  def basicNoWait(v: ThreadVerifier) {
    v.verify()
    noWaitInt.incrementAndGet()
  }

  def basicFuture(v: ThreadVerifier): Future[Int] = {
    v.verify()
    Promise.successful(1).future
  }

  def basicWait(v: ThreadVerifier): Int = {
    v.verify()
    1
  }
}

class TestClass(other: BasicTestClass) extends BasicTestClass {
  private def callOut[T](v: ThreadVerifier, f: (ThreadVerifier) => T): T = {
    v.verify()
    f(v.pop)
  }

  def callOutNoWait(v: ThreadVerifier) { callOut(v, other.basicNoWait(_)) }

  def callOutFuture(v: ThreadVerifier): Future[Int] = {
    callOut(v, other.basicFuture(_))
  }

  def callOutWait(v: ThreadVerifier): Int = { callOut(v, other.basicWait(_)) }

  private def callSelf[T](v: ThreadVerifier, f: (ThreadVerifier) => T): T = {
    v.verify()
    val v2 = v.pop
    // Calls to self always happen in the same thread
    f(v2.copy(Some(false) :: v2.diffList.tail))
  }

  def callSelfNoWait(v: ThreadVerifier) { callSelf(v, basicNoWait(_)) }

  def callSelfFuture(v: ThreadVerifier): Future[Int] = {
    callSelf(v, basicFuture(_))
  }

  def callSelfWait(v: ThreadVerifier): Int = { callSelf(v, basicWait(_)) }
}

class ProxyActorIntegrationTests extends FunSuite with Timeouts with SpanSugar
with BeforeAndAfterAll {
  implicit val ec = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors))

  override protected def afterAll() {
    ec.shutdown()
  }

  private def behavior(name: String, diffList: List[Option[Boolean]],
                       doCallWait: Boolean,
                       context: ActorContext, contextOpt2: Option[ActorContext]) {
    test(name) {
      val context2 = contextOpt2.getOrElse(context)
      val basic = context.proxyActor[BasicTestClass]()
      val tester = context2.proxyActor[TestClass](Seq((basic, classOf[BasicTestClass])))

      // This must be done here vs. in the instance because we want the proxied
      // futures, not the original ones
      val futureInt = new AtomicReference[List[Future[Int]]](List.empty)
      var schedFutList = List.empty[Future[Unit]]

      def schedule(diffList: List[Option[Boolean]])
                          (f: (ThreadVerifier) => Unit)
                          (implicit ec: ExecutionContext) {
        val fut = future {
          try {
            failAfter(100.milliseconds) { f(ThreadVerifier(diffList)) }
          } catch  {
            case e: Exception =>
              e.printStackTrace()
              throw e
          }
        }
        schedFutList = fut :: schedFutList
      }

      @tailrec
      def addFuture(fut: Future[Int]) {
        val list = futureInt.get
        if (!futureInt.compareAndSet(list, fut :: list)) addFuture(fut)
      }

      val iterations = 10000

      for (i <- 1 to iterations) {
        schedule(diffList) { tester.basicNoWait(_) }
        schedule(diffList) { t => addFuture(tester.basicFuture(t)) }
        schedule(diffList) { t => assert(tester.basicWait(t) === 1) }

        schedule(diffList) { tester.callOutNoWait(_) }
        schedule(diffList) { t => addFuture(tester.callOutFuture(t)) }
        if (doCallWait)
          schedule(diffList) { t => assert(tester.callOutWait(t) === 1) }

        schedule(diffList) { tester.callSelfNoWait(_) }
        schedule(diffList) { t => addFuture(tester.callSelfFuture(t)) }
        schedule(diffList) { t => assert(tester.callSelfWait(t) === 1) }
      }

      failAfter(20.seconds) {
        // Wait for all tests to run - isn't there a 'waitAll' function???
        schedFutList.foreach { Await.result(_, Duration.Inf) }

        // Verify correct # of futures and their values
        assert(futureInt.get.size === (iterations * 3))
        futureInt.get.foreach { f => assert(Await.result(f, Duration.Inf) === 1) }
      }

      assert(tester.noWaitInt.get === (iterations * 2))
      assert(basic.noWaitInt.get === (iterations))

      actorsFinished(basic, tester)
    }
  }

  testsFor(behavior("Single Threaded Pool Per Instance",
    List(Some(true), Some(true)), // 1st and 2nd call always diff thread
    doCallWait=true,              // No chance of deadlock since sep. pools
    singleThreadContext, Some(singleThreadContext)))

  testsFor(behavior("Same Thread",
    List(Some(false), Some(false)), // 1st and 2nd call always same thread
    doCallWait=true,                // No chance of deadlock since same thread
    sameThreadContext, None))

  testsFor(behavior("One Single Thread Pool For All",
    List(Some(true), Some(false)),  // 1st call always diff, 2nd call always same
    doCallWait=false,        // Would deadlock due to both using same thread pool
    singleThreadContext, None))

  testsFor(behavior("All Cores Thread Pool For All",
    List(Some(true), None), // 1st call always diff, 2nd call *could* be same
    doCallWait=false,       // Would deadlock due to both using same thread pool
    allCoresContext, None))

  testsFor(behavior("Cached Thread Pool For All",
    List(Some(true), None), // 1st call always diff, 2nd call *could* be same
    doCallWait=false,       // Would deadlock due to both using same thread pool
    cachedThreadContext, None))
}