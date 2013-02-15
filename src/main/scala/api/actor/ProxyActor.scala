/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/13/13
 * Time: 10:40 PM
 */

package api.actor

import java.lang.reflect.Method
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Await, future}
import scala.concurrent.duration.Duration
import net.sf.cglib.proxy.{Enhancer, MethodProxy, MethodInterceptor}

object ProxyActor {
  implicit val DefaultExecutor =
    ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor)

  private class Intercepter(waitAll: Boolean)
                           (implicit ec: ExecutionContext) extends MethodInterceptor {
    def intercept(obj:        AnyRef,
                  method:     Method,
                  args:       Array[AnyRef],
                  methProxy:  MethodProxy): AnyRef = {
      val fut = future {
        obj.synchronized { methProxy.invokeSuper(obj, args) }
      }

      if (method.getReturnType == Void.TYPE && !waitAll) null
      else Await.result(fut, Duration.Inf)
    }
  }

  def apply[T](clazz: Class[T], args: Seq[(Any,Class[_])] = Seq.empty,
               waitAll: Boolean = false)
              (implicit ec: ExecutionContext): T = {
    val enhancer = new Enhancer
    enhancer.setUseFactory(false)
    enhancer.setSuperclass(clazz)
    enhancer.setInterceptDuringConstruction(false)
    enhancer.setCallback(new Intercepter(waitAll))
    val (arg, types) = args.unzip
    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class
    enhancer.create(types.toArray,
      arg.toArray.asInstanceOf[Array[AnyRef]]).asInstanceOf[T]
  }
}