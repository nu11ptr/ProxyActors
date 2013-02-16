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
import net.sf.cglib.proxy.{MethodProxy, MethodInterceptor, Enhancer}

package object actor {
  lazy val singleThreadPool = createSingleThreadPool

  lazy val sameThread = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) { command.run() }
  })

  def createSingleThreadPool =
    ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor)

  private class Intercepter(waitAll: Boolean)
                           (implicit ec: ExecutionContext) extends MethodInterceptor {
    def intercept(obj:        AnyRef,
                  method:     Method,
                  args:       Array[AnyRef],
                  methProxy:  MethodProxy): AnyRef = {
      if (method.getAnnotation(classOf[omit]) == null) {
        val returnType = method.getReturnType
        val promise: Promise[AnyRef] =
          if (returnType == classOf[Future[AnyRef]]) Promise() else null

        val fut = future {
          val retVal = obj.synchronized { methProxy.invokeSuper(obj, args) }

          if (promise != null)
            promise.completeWith(retVal.asInstanceOf[Future[AnyRef]])
          else retVal
        }

        if (returnType == Void.TYPE && !waitAll) null
        else if (promise != null) promise.future
        else Await.result(fut, Duration.Inf)
      } else methProxy.invokeSuper(obj, args)
    }
  }

  private val contextSet = new AtomicReference(Set.empty[ExecutionContext])

  @tailrec
  private def updateContextSet(context: ExecutionContext) {
    val set = contextSet.get

    if (!set.contains(context) && !contextSet.compareAndSet(set, set + context))
      updateContextSet(context)
  }

  def proxyActor[T](clazz:    Class[T],
                    args:     Seq[(Any,Class[_])] = Seq.empty,
                    context:  ExecutionContext = createSingleThreadPool,
                    waitAll:  Boolean = false): T = {
    val enhancer = new Enhancer
    enhancer.setUseFactory(false)
    enhancer.setSuperclass(clazz)
    enhancer.setInterceptDuringConstruction(false)
    // Yes, this means we hang on to each context forever (for now)
    updateContextSet(context)
    enhancer.setCallback(new Intercepter(waitAll)(context))
    val (arg, types) = args.unzip
    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create(types.toArray,
      arg.toArray.asInstanceOf[Array[AnyRef]]).asInstanceOf[T]
  }

  def shutdown() {
    contextSet.get.foreach {
      case e: ExecutionContextExecutorService => e.shutdown()
      case _                                  =>
    }

    contextSet.set(Set.empty)
  }
}