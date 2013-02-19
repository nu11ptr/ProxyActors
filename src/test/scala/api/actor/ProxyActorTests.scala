/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/15/13
 * Time: 11:29 PM
 */

package api.actor

import scala.language.postfixOps
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits._
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
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

class ProxyActorTests extends FunSuite with Timeouts with SpanSugar
    with BeforeAndAfterAll {

  override protected def afterAll() { println("shutdown"); shutdown() }

  private def doTest(name: String, diffList: List[Option[Boolean]],
                     doCallWait: Boolean, basic: BasicTestClass,
                     tester: TestClass) {
    test(name) {
      println("started test")

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

      val iterations = 2500

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
    }
  }

  private def runTest(name:       String,
                      diffList:   List[Option[Boolean]],
                      doCallWait: Boolean,
                      context:    () => ExecutionContext) {
    val basic = proxyActor[BasicTestClass](context = context())
    val tester = proxyActor[TestClass](Seq((basic, classOf[BasicTestClass])),
                                       context())
    testsFor(doTest(name, diffList, doCallWait, basic, tester))
  }

  runTest("Single Threaded Pool Per Instance",
    List(Some(true), Some(true)), // 1st and 2nd call always diff thread
    doCallWait=true,              // No chance of deadlock since sep. pools
    createSingleThreadPool _)

  runTest("Same Thread",
    List(Some(false), Some(false)), // 1st and 2nd call always same thread
    doCallWait=true,                // No chance of deadlock since same thread
    () => sameThread)

  runTest("One Single Thread Pool For All",
    List(Some(true), Some(false)),  // 1st call always diff, 2nd call always same
    doCallWait=false,        // Would deadlock due to both using same thread pool
    () => singleThreadPool)

  runTest("All Cores Thread Pool For All",
    List(Some(true), None), // 1st call always diff, 2nd call *could* be same
    doCallWait=false,       // Would deadlock due to both using same thread pool
    () => allCoresThreadPool)
}