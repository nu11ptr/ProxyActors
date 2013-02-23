/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api.actor

import org.scalatest.FunSuite
import org.scalamock.scalatest.MockFactory

class RouterTests extends FunSuite with MockFactory {
  private var actor1, actor2: ActorSupport = _
  private var handler1, handler2: Handler = _

  // NOTE: ScalaMock throws NullPointerException when using BeforeAndAfter trait
  private def create() {
    // NOTE: Hack! ScalaMock can't (yet) mock classes with parameters
    class NewHandler extends Handler(sameThreadContext)
    handler1 = mock[NewHandler]
    handler2 = mock[NewHandler]
    actor1 = mock[ActorSupport]
    actor2 = mock[ActorSupport]
  }

  test("Default Algorithm: Pick first when it has zero score") {
    create()
    (actor1.$handler$ _).expects().returning(handler1)
    (actor2.$handler$ _).expects().never
    (handler1.serviceCount _).expects().returning(0)

    expectResult(actor1)(defaultAlg(List(actor1, actor2)))
  }

  test("Default Algorithm: Pick first actor with zero score") {
    create()
    (actor1.$handler$ _).expects().returning(handler1)
    (actor2.$handler$ _).expects().returning(handler2)
    (handler1.serviceCount _).expects().returning(1)
    (handler2.serviceCount _).expects().returning(0)

    expectResult(actor2)(defaultAlg(List(actor1, actor2)))
  }

  private def lowestScore(num: Int, exp: => ActorSupport, score1: Int, score2: Int) {
    test("Default Algorithm: Pick lowest score when not zero #" + num) {
      create()
      (actor1.$handler$ _).expects().returning(handler1)
      (actor2.$handler$ _).expects().returning(handler2)
      (handler1.serviceCount _).expects().returning(score1)
      (handler2.serviceCount _).expects().returning(score2)

      expectResult(exp)(defaultAlg(List(actor1, actor2)))
    }
  }

  testsFor(lowestScore(1, actor1, score1 = 1, score2 = 2))
  testsFor(lowestScore(2, actor2, score1 = 2, score2 = 1))
}