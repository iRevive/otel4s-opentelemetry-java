/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.catseffect.v3_6

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import io.opentelemetry.api.trace.{Span, SpanContext}
import io.opentelemetry.context.{Context, ContextKey}
import io.opentelemetry.instrumentation.testing.junit._
import io.opentelemetry.sdk.testing.assertj.{SpanDataAssert, TraceAssert}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.{Test, TestInstance}

import java.util.function.Consumer
import scala.concurrent.duration._

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FiberScopeRestorationTest {
  @RegisterExtension
  val testing: InstrumentationExtension = AgentInstrumentationExtension.create()

  private val key = ContextKey.named[String]("scope-restoration-key")
  private type Snapshot = (SpanContext, String)
  private def snapshot: IO[Snapshot] = IO((Span.current().getSpanContext, Context.current().get(key)))

  // An application Context.root().makeCurrent() goes through the API bridge first and normalizes
  // the root. Invoke the bootstrap agent API directly to exercise the raw root used by Netty.
  private def rootScope: IO[AutoCloseable] = IO {
    val cls = Class.forName("io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Context")
    cls.getMethod("makeCurrent").invoke(cls.getMethod("root").invoke(null)).asInstanceOf[AutoCloseable]
  }
  private def suppressed: Resource[IO, AutoCloseable] =
    Resource.make(rootScope)(scope => IO(scope.close()))

  private def assertSuppressed: IO[Unit] = snapshot.map { current =>
    assertFalse(current._1.isValid)
    assertNull(current._2)
  }

  @Test
  def restoresNestedRootScopesAndApplicationKeysAfterYield(): Unit = withParent { before =>
    for {
      _ <- suppressed.use { _ =>
        assertSuppressed *> suppressed.use(_ => IO.cede *> assertSuppressed) *> assertSuppressed
      }
      after <- snapshot
      _ <- IO(assertEquals(before, after))
    } yield ()
  }

  @Test
  def closingScopeTwiceDoesNotClobberAnotherScope(): Unit = withParent { before =>
    for {
      first <- rootScope
      _ <- IO(first.close())
      _ <- suppressed.use { _ => IO(first.close()) *> assertSuppressed }
      after <- snapshot
      _ <- IO(assertEquals(before, after))
    } yield ()
  }

  @Test
  def ignoresOutOfOrderCloseUntilInnerScopeIsClosed(): Unit = withParent { before =>
    for {
      _ <- suppressed.use { outer =>
        Resource.make(IO(Context.current().`with`(key, "inner").makeCurrent()))(scope => IO(scope.close()))
          .use { _ =>
            IO(outer.close()) *> snapshot.map { current =>
              assertFalse(current._1.isValid)
              assertEquals("inner", current._2)
            }
          } *> assertSuppressed
      }
      after <- snapshot
      _ <- IO(assertEquals(before, after))
    } yield ()
  }

  @Test
  def restoresContextOnError(): Unit = withParent { before =>
    val error = new RuntimeException("expected")
    for {
      result <- suppressed.use(_ => IO.cede *> IO.raiseError[Unit](error)).attempt
      _ <- IO(assertEquals(Left(error), result))
      after <- snapshot
      _ <- IO(assertEquals(before, after))
    } yield ()
  }

  @Test
  def restoresCanceledFiberWithoutChangingParentFiber(): Unit = withParent { before =>
    for {
      entered <- Deferred[IO, Unit]
      restored <- Deferred[IO, Snapshot]
      _ <- Resource.make(
        Resource.make(rootScope) { scope =>
          IO(scope.close()) *> snapshot.flatMap(restored.complete).void
        }.use(_ => entered.complete(()) *> IO.never[Unit]).start
      )(_.cancel).use { fiber =>
        for {
          _ <- entered.get
          _ <- fiber.cancel
          childAfter <- restored.get
          parentAfter <- snapshot
          _ <- IO {
            assertEquals(before, childAfter)
            assertEquals(before, parentAfter)
          }
        } yield ()
      }
    } yield ()
  }

  private def withParent(body: Snapshot => IO[Unit]): Unit = {
    testing.runWithSpan[Exception]("parent", () => {
      Resource.make(IO(Context.current().`with`(key, "value").makeCurrent()))(scope => IO(scope.close()))
        .use { _ =>
          snapshot.flatMap { before =>
            IO { assertTrue(before._1.isValid); assertEquals("value", before._2) } *> body(before)
          }
        }.timeout(10.seconds).unsafeRunSync()
    })
    testing.waitAndAssertTraces(new Consumer[TraceAssert] {
      def accept(trace: TraceAssert): Unit = {
        trace.hasSpansSatisfyingExactly(new Consumer[SpanDataAssert] {
          def accept(span: SpanDataAssert): Unit = { span.hasName("parent").hasNoParent; () }
        })
        ()
      }
    })
  }
}
