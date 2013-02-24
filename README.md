ProxyActors
===========

A simple, lightweight typed actor framework for Scala. It includes an optional
load balancer for transparently load balancing typed actor workloads. The
current implementation is only ~150 SLOC in a single file. The goal of the
project is to meet some specific use cases of our company (see 'Why?' section),
but we are hoping you will find it useful as well.

Installation
============

* Requires Scala 2.10 (which requires Java 1.6) and CGLib (tested on v2.2.2)

**Option 1: SBT (NOTE: Doesn't work yet - awaiting release into Maven Central)**

    libraryDependencies += "com.api-tech" % "proxyactors_2.10" % "0.1.0"

**Option 2: Copy the file into your project (you'll still need CGLib)**

Copy the 'package.scala' file into the folder for package api.actor in your
project. Optionally, change the package to match your organization.

**Option 3: [Download](https://bitbucket.org/apitech/proxyactors/downloads/proxyactors_2.10-0.1.0.jar) the jar (you'll still need CGLib)**

Examples
========

First, the obligatory 'hello world'. The async. hello is called first, but
printed after the synchronous hello due to being delayed and executed in a
different thread.

    import api.actor._  // Single import line

    class HelloWorld {
        def async() { Thread.sleep(100); println("Hello World! (async)") }
    }

    val hello = singleThreadContext.proxyActor[HelloWorld]()

    hello.async()
    println("Hello World! (sync)")

    actorsFinished(hello)   // Blocks until asyncHello is complete

The output is:

    Hello World! (sync)
    Hello World! (async)


Next, a quick example demonstrating typed actor load balancing. This example
doubles all numbers 1 to 1000 in parallel and then adds them up and prints the
result.

    import scala.concurrent.{Await, Future, Promise}
    import scala.concurrent.duration.Duration
    import api.actor._

    trait Doubler { def double(n: Int): Future[Int] }

    class MyDoubler extends Doubler {
        def double(n: Int): Future[Int] = { Promise.successful(n + n).future }
    }

    // Create one thread and actor per logical CPU thread
    val actors = allCoresContext.proxyActors[MyDoubler](qty = totalCores)
    val doubler = proxyRouter[Doubler](actors)

    val futures = for (i <- 1 to 1000) yield doubler.double(i)
    val total = futures.foldLeft(0) {
        (sum, fut) => Await.result(fut, Duration.Inf) + sum
    }

    println(s"1 to 1000 doubled and then summed equals $total")

    actorsFinished(actors)

The output is:

    1 to 1000 doubled and then summed equals 1001000

Features
=========

* Extremely small and lightweight
* Learn the API in minutes
* Typed actor router/load balancer with the distribution algorithm as a
simple function
* Actors extend your classes - no way to leak non-actor reference
* Utilizes Scala 2.10 Futures/ExecutorContexts
* Thread pools auto shutdown after last actor signals it is finished
* CGLib is the only dependency (other than Scala library)
* Ability to wrap callback objects in a mutual exclusion proxy (when used with
threaded libraries). By default, this happens without additional threading
overhead.

Typed Actors?
=============

There are better sites for understanding all the theory. Definitely google them
to get the 'big picture'. We'll just cover how our library works in few
bullet points, and hopefully you can see why something like this makes sense.

* The base of a typed actor is your regular Scala class with no special features
* We create a proxy of that class and instantiate it with the args of your choosing
* This proxy can now be used just like the regular object with these differences:
    * We lock them for mutual exclusion - they are guaranteed to only execute in one
thread at a time. You don't need to lock your class's mutable data anymore.
    * They are executed in a thread pool of your choosing (one pool for all actors,
single thread per actor, or any other combination)
    * Methods that return Unit or a Scala future execute asynchronously in the pool
    * Methods that return values will still operate synchronously, however (with
   exception of Scala futures).
* When you are done with an actor, you call 'actorFinished'. When all actors
finish that were using a thread pool, that thread pool is automatically freed.
'actorFinished' will block as needed if not all actor methods have finished
execution.

Why?
====

The first thing that probably comes to mind for Scala typed actors is Akka.
Akka is great. We like Akka. If you need its many features, you should use it
too. Our library has probably not even 2% the features of Akka (and never will!).
 Clear enough? :-)

That said, Akka is a large, diverse collection of parallel compute,
synchronization paradigms, and much more. Sometimes this is what we need, and
sometimes we just need something very small, simple, and focused.

Here are the specific things we needed for our projects:

### Typed actors that 'extend' our classes, not 'wrap' them ###

Yes, we know this seems backwards with all the hype of composition over
inheritance, but we have a good reason for wanting this. Due to the Scala/Java
implicit 'this' reference, it is very easy to leak 'this' outside the object
when registering for callbacks, etc. Additionally, we don't like giving our
classes knowledge that they are a typed actor even if just for identity
purposes. Yes, we can abstract that, but it is more convoluted. Extending isn't
always great either - you can't extend final classes/methods, etc (it is
possible we'll add wrapping as an alternative in a future release).

### Typed actor routing/load balancing ###

We wanted to be able to take a list of typed actors and load balance to them
as a group. We wanted to do it without a lot of work or boilerplate. Lastly,
we wanted a solid default load balancing algorithm that would make it easy to
load balance parallel workloads.

### Easy mutual exclusion without boilerplate ###

Not every program needs to be parallel. Due to Java having first-class threads
since it's inception, several libraries have shipped with embedded threading
for concurrency purposes. Even if you are writing a simple script that takes
callbacks, it is very likely you'll need to think about mutual exclusion.
Using a simple 'proxyActor' function call, you can wrap these callback objects
in an actor that is designed for mutual exclusion, and by default, operates
in the same thread it was called by. Since it is very coarse locking via proxy,
you'll lose some performance/parallelism, but for many programs that just isn't
a concern.

### Very easy, fast, and small ###

The goal is to make every feature easy to use without needing to reach for
the ScalaDoc. We wanted an API that fit in our heads. We only want things in
the library that we will use.

Our jar is currently ~40K and includes examples. The code itself is ~150 SLOC in
a single file, so no worries about tracking a bug through thousands of lines
of code. You can even just copy the single file right into your programs.
(unfortunately, you'd still need to have the CGLib jar)

Performance
===========

We haven't done any benchmarks yet. No doubt we will eventually, but it isn't high
priority for us as our workloads either a) do a ton of work per actor call or b)
aren't performance sensitive. Regardless, since we use a proxy that uses
reflection, you will want to do as much work as possible per call to call
offset the overhead in performance sensitive workloads.

Links
=====

Primary: <https://bitbucket.org/apitech/proxyactors>

Mirror: <https://github.com/nu11ptr/ProxyActors>

ScalaDoc: <http://apitech.bitbucket.org/proxyactors/scaladoc/#api.actor.package>

Downloads: <https://bitbucket.org/apitech/proxyactors/downloads>

License
=======

ProxyActors is release under a modified BSD license. This means you can use it
in open source or commerical programs without the need to release your code. All
we ask is that you maintain our copyright notice.

Contributions
=============

Regretfully, we are unable to accept code contributions at this time. The
library currently does what we need it to do, and we wish to retain the sole
copyright on the the code. Please do submit bug reports, fork, and change the
code as you need, but please understand that pull requests will be refused.