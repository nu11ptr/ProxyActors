/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/14/13
 * Time: 11:37 PM
 */

package api

import scala.annotation.tailrec

import java.lang.reflect.Method
import java.util.concurrent.{Executor, Executors }
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.reflect.{ClassTag, classTag}
import net.sf.cglib.proxy.{MethodProxy, MethodInterceptor, Enhancer}

package object actor {
  // *** Thread Pools ***
  lazy val singleThreadPool = createSingleThreadPool

  lazy val sameThread = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) { command.run() }
  })

  lazy val cachedThreadPool =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  lazy val allCoresThreadPool = createFixedThreadPool()

  def createSingleThreadPool =
    ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor)

  def createFixedThreadPool(thr: Int = Runtime.getRuntime.availableProcessors) =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(thr))

  // *** Method Interception ***
  private class Intercepter(waitAll: Boolean)
                           (implicit ec: ExecutionContext) extends MethodInterceptor {
    def intercept(obj:        AnyRef,
                  method:     Method,
                  args:       Array[AnyRef],
                  methProxy:  MethodProxy): AnyRef = {
      if (method.getAnnotation(classOf[omit]) == null && !Thread.holdsLock(obj)) {
        val returnType = method.getReturnType
        // We proxy the actual future object of the callee with our own
        val promise: Promise[AnyRef] =
          if (returnType == classOf[Future[AnyRef]]) Promise() else null

        val fut = future {
          // We synchronize on the called object to make sure that the
          // called object never allows more than one caller at a time
          val retVal = obj.synchronized { methProxy.invokeSuper(obj, args) }

          if (promise != null)
            // Our promise mimics the result of the actual future
            promise.completeWith(retVal.asInstanceOf[Future[AnyRef]])
          else retVal
        }

        // Fire and forget for Unit returning methods
        if (returnType == Void.TYPE && !waitAll) null
        // Return our proxy future for Future returning methods
        else if (promise != null && !waitAll) promise.future
        // Block until the computation done for anything else
        else Await.result(fut, Duration.Inf)
      // If omitted, call superclass method inline in this thread
      } else obj.synchronized { methProxy.invokeSuper(obj, args) }
    }
  }

  // *** Proxy Creation ***
  private val contextSet = new AtomicReference(Set.empty[ExecutionContext])

  @tailrec
  private def updateContextSet(context: ExecutionContext) {
    val set = contextSet.get

    if (!set.contains(context) && !contextSet.compareAndSet(set, set + context))
      updateContextSet(context)
  }

  def proxyActor[T: ClassTag](
      args:     Seq[(Any,Class[_])] = Seq.empty,
      context:  ExecutionContext = createSingleThreadPool,
      waitAll:  Boolean = false): T = {
    val enhancer = new Enhancer
    // We don't need it and keeps proxy identity a bit more private
    enhancer.setUseFactory(false)
    enhancer.setSuperclass(classTag[T].runtimeClass)
    enhancer.setInterceptDuringConstruction(true)
    // Yes, this means we hang on to each context until shutdown called
    updateContextSet(context)
    // Each instance of each extended class gets own intercepter instance
    enhancer.setCallback(new Intercepter(waitAll)(context))
    val (arg, types) = args.unzip
    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create(types.toArray,
      arg.toArray.asInstanceOf[Array[AnyRef]]).asInstanceOf[T]
  }

  // *** Shutdown/Cleanup ***
  def shutdown() {
    // Only shutdown a thread pool if it supports that feature
    contextSet.get.foreach {
      case e: ExecutionContextExecutorService => e.shutdown()
      case _                                  =>
    }

    // Release references for all thread pools
    contextSet.set(Set.empty)
  }
}