/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.reflect.{ClassTag, classTag}
import java.lang.reflect.Method
import java.util.concurrent.{TimeUnit, Executor, Executors}
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger
import net.sf.cglib.proxy._

/** Package object holding all methods for actor lifecycle management */
package object actor {
  // *** Thread Pools ***
  private lazy val sameThread = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) { command.run() }
  })

  private def singleThreadPool =
    ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor)

  private def fixedThreadPool(qty: Int) =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(qty))

  /** Total number of logical CPU threads in the user's computer */
  def totalCores = Runtime.getRuntime.availableProcessors

  private def allCoresThreadPool = fixedThreadPool(totalCores)

  private def cachedThreadPool =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  // *** Contexts ***
  /** Class that holds an actor's thread pool. The constructor is private, so
    * use the helper methods to create. */
  class ActorContext private[actor] (_ec: => ExecutionContext) {
    private val refCount = new AtomicInteger(0)

    private[actor] lazy val ec: ExecutionContext = _ec

    private def shutdown() {
      // We only actually shutdown an ec if it supports it
      ec match {
        case e: ExecutionContextExecutorService =>
          e.shutdown()
          e.awaitTermination(Long.MaxValue, TimeUnit.DAYS)
        case _                                  =>
      }
    }

    private[actor] def incRef() { refCount.incrementAndGet() }

    private[actor] def decRef() { if (refCount.decrementAndGet == 0) shutdown() }

    /** Creates a proxy typed actor by dynamically generating a new proxy that
      * extends class T and uses the actor context used to invoke this method
      *
      * @param args     List of values to pass to T's constructor for each actor
      *                 instance
      * @param types    List of types to locate the correct constructor. May be
      *                 omitted for single constructor classes
      * @tparam T       Class the typed proxy actor should extend
      * @return         New proxy typed actor
      */
    def proxyActor[T: ClassTag](args:  Seq[AnyRef] = Seq.empty,
                                types: Seq[Class[_]] = Seq.empty): T =
      api.actor.proxyActor(args, types, this)

    /** Creates a list of proxy typed actors by dynamically generating a new
      * proxy that extends class T and uses the actor context used to invoke
      * this method
      *
      * @param qty      Quantity of typed actors to create
      * @param args     List of values to pass to T's constructor for each actor
      *                 instance
      * @param types    List of types to locate the correct constructor. May be
      *                 omitted for single constructor classes
      * @tparam T       Class the typed proxy actors should extend
      * @return         List of new proxy typed actors
      */
    def proxyActors[T: ClassTag](qty:   Int,
                                 args:  Seq[AnyRef] = Seq.empty,
                                 types: Seq[Class[_]] = Seq.empty): List[T] =
      api.actor.proxyActors(qty, args, types, this)
  }

  /** Creates a new actor context using a user specified ExecutionContext
   *
   * @param ec ExecutionContext to wrap
   * @return New actor context
   */
  def actorContext(ec: => ExecutionContext) = new ActorContext(ec)

  /** Thread context that doesn't uses a pool but uses the calling thread
    * for execution. The value in this is that the the called object is locked
    * thus providing easy synchronization
    */
  lazy val sameThreadContext = actorContext(sameThread)

  /** Thread context using underlying Java single thread pool */
  def singleThreadContext = actorContext(singleThreadPool)

  /** Thread context using underlying Java fixed thread pool with user
    * specified quantity
    *
    * @param qty Numer of threads in the pool
    */
  def fixedThreadContext(qty: Int) = actorContext(fixedThreadPool(qty))

  /** Thread context using underlying Java fixed thread pool with a thread
    * for each logical CPU thread */
  def allCoresContext = actorContext(allCoresThreadPool)

  /** Thread context using underlying Java cached thread pool */
  def cachedThreadContext = actorContext(cachedThreadPool)

  // *** Proxy Method Handling ***
  private[actor] trait ActorSupport {
    def $handler$: Handler
  }

  private[actor] class Handler(val ac: ActorContext) {
    private val lock = new ReentrantLock
    private val count = new AtomicInteger(0)

    def lockContention: Int = if (lock.isLocked) 1 + lock.getQueueLength else 0

    def serviceCount: Int = count.get

    private[actor] val handlerCallback = new FixedValue {
      def loadObject: AnyRef = Handler.this
    }

    private[actor] val interceptor = new MethodInterceptor {
      def intercept(obj:        AnyRef,
                    method:     Method,
                    args:       Array[AnyRef],
                    methProxy:  MethodProxy): AnyRef = {
        def invokeSuperWithLock(): AnyRef = {
          // We lock to make sure that the called object never allows more
          // than one caller at a time
          lock.lock()
          try { methProxy.invokeSuper(obj, args) } finally { lock.unlock() }
        }

        count.incrementAndGet

        // Calling ourself?
        if (!lock.isHeldByCurrentThread) {
          val returnType = method.getReturnType

          // We proxy the actual future object of the callee with our own
          val promiseOpt: Option[Promise[AnyRef]] =
            if (returnType == classOf[Future[AnyRef]]) Some(Promise()) else None

          val fut = future {
            try {
              val retVal = invokeSuperWithLock()

              promiseOpt match {
                case Some(promise) =>
                  // Our promise mimics the result of the actual future
                  promise.completeWith(retVal.asInstanceOf[Future[AnyRef]])
                case None          =>
                  retVal
              }
            } finally { count.decrementAndGet }
          }(ac.ec)

          // Fire and forget for Unit returning methods
          if (returnType == Void.TYPE) null
          // Return our proxy future for Future returning methods
          else if (promiseOpt.isDefined) promiseOpt.get.future
          // Block until the computation is done for anything else
          else Await.result(fut, Duration.Inf)
        // If calling ourself, call superclass method inline in this same thread
        } else try { invokeSuperWithLock() } finally { count.decrementAndGet }
      }
    }
  }

  // *** Proxy Lifecycle ***
  /** Creates a proxy typed actor by dynamically generating a new proxy that
    * extends class T
    *
    * @param args     List of values to pass to T's constructor for each actor
    *                 instance
    * @param types    List of types to locate the correct constructor. May be
    *                 omitted for single constructor classes
    * @param context  (Optional) Actor context holding underlying thread pool for
    *                 actor execution
    * @tparam T       Class the typed proxy actor should extend
    * @return         New proxy typed actor
    */
  def proxyActor[T: ClassTag](args:    Seq[AnyRef] = Seq.empty,
                              types:   Seq[Class[_]] = Seq.empty,
                              context: ActorContext = sameThreadContext): T = {
    context.incRef()

    val enhancer = new Enhancer
    // We don't need it and keeps proxy identity a bit more private
    enhancer.setUseFactory(false)
    val baseClass = classTag[T].runtimeClass
    enhancer.setSuperclass(baseClass)
    enhancer.setInterceptDuringConstruction(true)
    // Wedge in our 'ActorSupport' - need this to get a copy of our handler
    enhancer.setInterfaces(Array(classOf[ActorSupport]))
    enhancer.setCallbackFilter(new CallbackFilter {
      def accept(method: Method) = if (method.getName == "$handler$") 0 else 1
    })
    val handler = new Handler(context)
    enhancer.setCallbacks(Array(handler.handlerCallback, handler.interceptor))
    enhancer.setCallbackTypes(Array(classOf[FixedValue], classOf[MethodInterceptor]))

    val typesArray = if (types.isEmpty) {
      val constructors = baseClass.getConstructors.toList
      require(constructors.size == 1, "Ambiguous constructor, specify types")
      constructors.head.getParameterTypes
    } else types.toArray

    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create(typesArray, args.toArray).asInstanceOf[T]
  }

  /** Creates a list of proxy typed actors by dynamically generating a new
    * proxy that extends class T
    *
    * @param qty      Quantity of typed actors to create
    * @param args     List of values to pass to T's constructor for each actor
    *                 instance
    * @param types    List of types to locate the correct constructor. May be
    *                 omitted for single constructor classes
    * @param context  (Optional) Actor context holding underlying thread pool for
    *                 actor execution
    * @tparam T       Class the typed proxy actors should extend
    * @return         List of new proxy typed actors
    */
  def proxyActors[T: ClassTag](qty:     Int,
                               args:    Seq[AnyRef] = Seq.empty,
                               types:   Seq[Class[_]] = Seq.empty,
                               context: ActorContext = sameThreadContext): List[T] =
    (for (i <- 1 to qty) yield proxyActor(args, types, context)).toList

  /** Call to cleanup actors when finished with them. When all actors using a
    * context have finished, the underlying thread pool will be stopped.
    *
    * @param actors (varargs) One or more actors that we are finished with
    */
  def actorsFinished[T](actors: T*) {
    actors.foreach { _.asInstanceOf[T with ActorSupport].$handler$.ac.decRef() }
  }

  /** Call to cleanup a list of actors when finished with them. When all actors
    * using a context have finished, the underlying thread pool will be stopped.
    *
    * @param actors List of actors that we are finished with
    */
  def actorsFinished[T](actors: List[T]) { actorsFinished(actors: _*) }

  // *** Router ***
  /** Default router load balancing function */
  def defaultAlg[T](choices: List[T]): T = {
    val first = choices.head.asInstanceOf[T with ActorSupport]
    val firstScore = first.$handler$.serviceCount

    if (firstScore == 0) first
    else
      choices.tail.foldLeft((first, firstScore)) {
        case (bestTup @ (best, score), curr) =>
          val candidate = curr.asInstanceOf[T with ActorSupport]
          val candScore = candidate.$handler$.serviceCount

          if (candScore == 0) return candidate
          else if (candScore < score) (candidate, candScore)
          else bestTup
      }._1
  }

  /** Type used for router algorithm functions */
  type RouterAlg[T] = (List[T]) => T

  /** Creates a new router for load balancing work to a list of typed actors
    *
    * @param actors  List of typed actors to route to - must have mixed in trait T
    * @param alg     (Optional) A function used to determine actor load balancing
    * @tparam T      Interface router uses to discover methods it must intercept
    * @return        New proxy object extending trait T to be used as a router
    */
  def proxyRouter[T](actors:  List[T])
                    (implicit alg: RouterAlg[T] = defaultAlg[T] _,
                              tag: ClassTag[T]): T = {
    require(actors.nonEmpty, "List of actors can't be empty.")

    val enhancer = new Enhancer
    // We don't need it and keeps proxy identity a bit more private
    enhancer.setUseFactory(false)
    // Bogus superclass - AnyRef seemed a good pick
    enhancer.setSuperclass(classOf[AnyRef])
    enhancer.setInterceptDuringConstruction(false)
    enhancer.setInterfaces(Array(classTag[T].runtimeClass))
    enhancer.setCallback(new MethodInterceptor {
      def intercept(obj:        AnyRef,
                    method:     Method,
                    args:       Array[AnyRef],
                    methProxy:  MethodProxy): AnyRef =
        methProxy.invoke(alg(actors), args)
    })

    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create.asInstanceOf[T]
  }
}