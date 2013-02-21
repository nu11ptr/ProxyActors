/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/14/13
 * Time: 11:37 PM
 */

package api

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.reflect.{ClassTag, classTag}
import scala.annotation.tailrec
import java.lang.reflect.Method
import java.util.concurrent.{ExecutorService, Executor, Executors}
import java.util.concurrent.locks.ReentrantLock
import net.sf.cglib.proxy._

package object actor {
  // *** Thread Pools ***
  lazy val sameThread = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) { command.run() }
  })

  def singleThreadPool =
    ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor)

  def fixedThreadPool(qty: Int) =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(qty))

  def allCoresThreadPool = fixedThreadPool(Runtime.getRuntime.availableProcessors)

  def cachedThreadPool =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  // *** Contexts ***
  class ActorContext(private val ec: ExecutionContext) {
    def shutdown() {
      // We only actually shutdown an ec if it supports it
      ec match {
        case e: ExecutionContextExecutorService => e.shutdown()
        case _                                  =>
      }
    }

    def proxyActor[T: ClassTag](args: Seq[(Any,Class[_])] = Seq.empty): T = {
      implicit val context = ec
      api.actor.proxyActor(args)
    }

    def proxyActors[T: ClassTag](qty: Int, args: Seq[(Any,Class[_])] = Seq.empty)
    : List[T] = {
      implicit val context = ec
      api.actor.proxyActors(qty, args)
    }
  }

  def actorContext(ec: ExecutionContext) = new ActorContext(ec)

  // TODO: Some Executors are also an ExecutorContext (after scala wraps them)
  // Look into converting the below into an implicit conversion
  def actorContext(e: Executor) = new ActorContext(e match {
    case es: ExecutorService  => ExecutionContext.fromExecutorService(es)
    case e:  Executor         => ExecutionContext.fromExecutor(e)
  })

  lazy val sameThreadContext = actorContext(sameThread: ExecutionContext)

  def singleThreadContext = actorContext(singleThreadPool: ExecutionContext)

  def fixedThreadContext(qty: Int) = actorContext(fixedThreadPool(qty): ExecutionContext)

  def allCoresContext = actorContext(allCoresThreadPool: ExecutionContext)

  def cachedThreadContext = actorContext(cachedThreadPool: ExecutionContext)

  // *** Proxy Method Handling ***
  private trait ActorSupport {
    def $handler$: Handler
  }

  private class Handler(implicit ec: ExecutionContext) {
    private val lock = new ReentrantLock

    def lockContention: Int = if (lock.isLocked) 1 + lock.getQueueLength else 0

    val handlerCallback = new FixedValue {
      def loadObject: AnyRef = Handler.this
    }

    val interceptor = new MethodInterceptor {
      def intercept(obj:        AnyRef,
                    method:     Method,
                    args:       Array[AnyRef],
                    methProxy:  MethodProxy): AnyRef = {
        def invokeSuperWithLock(): AnyRef = {
          lock.lock()
          try { methProxy.invokeSuper(obj, args) } finally { lock.unlock() }
        }

        if (!lock.isHeldByCurrentThread) {
          val returnType = method.getReturnType
          // We proxy the actual future object of the callee with our own
          val promise: Promise[AnyRef] =
            if (returnType == classOf[Future[AnyRef]]) Promise() else null

          val fut = future {
            // We synchronize on the called object to make sure that the
            // called object never allows more than one caller at a time
            val retVal = invokeSuperWithLock()

            if (promise != null)
              // Our promise mimics the result of the actual future
              promise.completeWith(retVal.asInstanceOf[Future[AnyRef]])
            else retVal
          }

          // Fire and forget for Unit returning methods
          if (returnType == Void.TYPE) null
          // Return our proxy future for Future returning methods
          else if (promise != null) promise.future
          // Block until the computation done for anything else
          else Await.result(fut, Duration.Inf)
        // If omitted, call superclass method inline in this thread
        } else invokeSuperWithLock()
      }
    }
  }

  private object Filter extends CallbackFilter {
    def accept(method: Method): Int = if (method.getName == "$handler$") 0 else 1
  }

  // *** Proxy Creation ***
  def proxyActor[T](args: Seq[(Any,Class[_])] = Seq.empty)
                   (implicit context: ExecutionContext, tag: ClassTag[T]): T = {
    val enhancer = new Enhancer
    // We don't need it and keeps proxy identity a bit more private
    enhancer.setUseFactory(false)
    enhancer.setSuperclass(classTag[T].runtimeClass)
    enhancer.setInterceptDuringConstruction(true)

    enhancer.setInterfaces(Array(classOf[ActorSupport]))
    enhancer.setCallbackFilter(Filter)
    val handler = new Handler()
    enhancer.setCallbacks(Array(handler.handlerCallback, handler.interceptor))
    enhancer.setCallbackTypes(Array(classOf[FixedValue], classOf[MethodInterceptor]))

    val (arg, types) = args.unzip
    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create(types.toArray,
      arg.toArray.asInstanceOf[Array[AnyRef]]).asInstanceOf[T]
  }

  def proxyActors[T](qty: Int, args: Seq[(Any,Class[_])] = Seq.empty)
                    (implicit context: ExecutionContext, tag: ClassTag[T])
  : List[T] = {
    @tailrec
    def buildProxyList(created: Int = 0, list: List[T] = Nil): List[T] =
      if (created < qty) buildProxyList(created + 1, proxyActor(args) :: list)
      else list

    buildProxyList()
  }

  // *** Router ***
  def defaultAlg[T](choices: List[T]): AnyRef = {
    val first = choices.head.asInstanceOf[ActorSupport]
    val firstScore = first.$handler$.lockContention

    if (firstScore == 0) first
    else
      choices.tail.foldLeft((first, firstScore)) {
        case (bestTup @ (best, score), curr) =>
          val candidate = curr.asInstanceOf[ActorSupport]
          val candScore = candidate.$handler$.lockContention

          if (candScore == 0) return candidate
          else if (candScore < score) (candidate, candScore)
          else bestTup
      }._1
  }

  type RouterAlg = () => AnyRef

  private class RouterInterceptor(private val alg: RouterAlg) extends MethodInterceptor {
    def intercept(obj:        AnyRef,
                  method:     Method,
                  args:       Array[AnyRef],
                  methProxy:  MethodProxy): AnyRef =
      methProxy.invoke(alg(), args)
  }

  def proxyRouter[T](routees: List[T])
                    (implicit alg: RouterAlg = () => defaultAlg(routees),
                              tag: ClassTag[T]): T = {
    require(routees.nonEmpty)

    val enhancer = new Enhancer
    // We don't need it and keeps proxy identity a bit more private
    enhancer.setUseFactory(false)
    enhancer.setSuperclass(classOf[AnyRef])
    enhancer.setInterceptDuringConstruction(false)
    enhancer.setInterfaces(Array(classTag[T].runtimeClass))
    enhancer.setCallback(new RouterInterceptor(alg))

    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create.asInstanceOf[T]
  }
}