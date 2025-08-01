/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.otel4s.v0_13

import cats.effect.{Deferred, Fiber, IO, SyncIO}
import cats.effect.unsafe.implicits.global
import io.opentelemetry.context.{Context => JContext}
import io.opentelemetry.instrumentation.testing.junit._
import org.typelevel.otel4s.context.{Key, LocalProvider}
import io.opentelemetry.instrumentation.testing.util.{
  TelemetryDataUtil,
  ThrowingRunnable
}
import io.opentelemetry.sdk.testing.assertj.{SpanDataAssert, TraceAssert}
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.Assertions.assertEquals
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{
  Context,
  IOLocalContextStorage,
  LocalContext
}
import org.typelevel.otel4s.trace.Tracer
import java.util.function.Consumer

import cats.effect.std.Dispatcher

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Using

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Otel4sIOLocalContextStorageInstrumentationTest {

  @RegisterExtension
  val testing: InstrumentationExtension = AgentInstrumentationExtension.create()

  //
  // Span tests
  //

  @Test
  def respectOuterSpan(): Unit = {
    testing.runWithSpan[Exception](
      "main_1_span_1",
      () => {
        runWithTracer { T: Tracer[IO] =>
          T.span("fiber_1_span_1").use_
        }
      }
    )

    testing.waitAndAssertTraces(
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("main_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_1").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  @Test
  def respectOuterSpanAndPropagateToLiftedFuture(): Unit = {
    testing.runWithSpan[Exception](
      "main_1_span_1",
      () => {
        runWithTracer { T: Tracer[IO] =>
          T.span("fiber_1_span_1").surround {
            IO.fromFuture(IO.delay {
              Future(
                testing.runWithSpan[Exception]("future_1_span_1", () => ())
              )(
                ExecutionContext.global
              )
            })
          }
        }
      }
    )

    testing.waitAndAssertTraces(
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("main_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_1").hasParent(trace.getSpan(0))),
          assertSpan(_.hasName("future_1_span_1").hasParent(trace.getSpan(1)))
        )
      }
    )
  }

  @Test
  def respectOuterSpanWithUnsafeRunToFuture(): Unit = {
    testing.runWithSpan[Exception](
      "main_1_span_1",
      () => {
        runWithTracer { T: Tracer[IO] =>
          IO.blocking {
            Await.result(
              T.span("fiber_1_span_1").use_.unsafeToFuture(),
              Duration.Inf
            )
          }
        }
      }
    )

    testing.waitAndAssertTraces(
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("main_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_1").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  @Test
  def respectOuterSpanWithDispatcher(): Unit = {
    testing.runWithSpan[Exception](
      "main_1_span_1",
      () => {
        runWithTracer { T: Tracer[IO] =>
          T.span("fiber_1_span_1").surround {
            Dispatcher.sequential[IO].use { dispatcher =>
              IO.blocking {
                dispatcher.unsafeRunSync(
                  T.span("dispatcher_1_span_1").use_
                )
              }
            }
          }
        }
      }
    )

    testing.waitAndAssertTraces(
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("main_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_1").hasParent(trace.getSpan(0))),
          assertSpan(
            _.hasName("dispatcher_1_span_1").hasParent(trace.getSpan(1))
          )
        )
      }
    )
  }

  @Test
  def traceIsPropagatedToChildFiber(): Unit = {
    runWithTracer { T: Tracer[IO] =>
      T.span("fiber_1_span_1").surround {
        for {
          child <- runWithSpan("fiber_2_span_1").start
          _ <- child.join
        } yield ()
      }
    }

    testing.waitAndAssertTraces(
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_2_span_1").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  @Test
  def traceIsPreservedWhenFiberIsInterrupted(): Unit = {
    runWithTracer { T: Tracer[IO] =>
      for {
        childStarted <- IO.deferred[Unit]
        _ <- T.span("fiber_1_span_1").surround {
          for {
            child <- IO.defer(
              testing.runWithSpan[IO[Fiber[IO, Throwable, Unit]], Exception](
                "fiber_2_span_1",
                () => (childStarted.complete(()) *> IO.never[Unit]).start
              )
            )
            _ <- childStarted.get
            _ <- child.cancel
          } yield ()
        }
      } yield ()
    }

    testing.waitAndAssertTraces(
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_2_span_1").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  @Test
  def synchronizedFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    runWithTracer { T: Tracer[IO] =>
      def runFiber(
          fiberNumber: Int,
          onStart: IO[Unit],
          onEnd: IO[Unit]
      ): IO[Any] =
        T.span(s"fiber_${fiberNumber}_span_1").surround {
          onStart *> runWithSpan(s"fiber_${fiberNumber}_span_2")
            .guarantee(onEnd)
        }

      for {
        fiber1Started <- IO.deferred[Unit]
        fiber2Done <- IO.deferred[Unit]

        fiber1 <- runFiber(
          fiberNumber = 1,
          onStart = fiber1Started.complete(()) *> fiber2Done.get,
          onEnd = IO.unit
        ).start

        fiber2 <- runFiber(
          fiberNumber = 2,
          onStart = fiber1Started.get,
          onEnd = fiber2Done.complete(()).void
        ).start

        _ <- fiber1.join *> fiber2.join
      } yield ()
    }

    testing.waitAndAssertSortedTraces(
      TelemetryDataUtil.orderByRootSpanName(
        "fiber_1_span_1",
        "fiber_1_span_2",
        "fiber_2_span_1",
        "fiber_2_span_2"
      ),
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_2").hasParent(trace.getSpan(0)))
        )
      },
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_2_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_2_span_2").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  @Test
  def concurrentFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    runWithTracer { T: Tracer[IO] =>
      def runFiber(
          fiberNumber: Int,
          start: Deferred[IO, Unit]
      ): IO[Unit] = {
        start.get *>
          T.span(s"fiber_${fiberNumber}_span_1").surround {
            IO.cede *> runWithSpan(s"fiber_${fiberNumber}_span_2")
          }
      }

      for {
        start <- IO.deferred[Unit]
        fiber1 <- runFiber(1, start).start
        fiber2 <- runFiber(2, start).start
        fiber3 <- runFiber(3, start).start
        _ <- start.complete(())
        _ <- fiber1.join *> fiber2.join *> fiber3.join
      } yield ()
    }

    testing.waitAndAssertSortedTraces(
      TelemetryDataUtil.orderByRootSpanName(
        "fiber_1_span_1",
        "fiber_2_span_1",
        "fiber_3_span_1"
      ),
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_2").hasParent(trace.getSpan(0)))
        )
      },
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_2_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_2_span_2").hasParent(trace.getSpan(0)))
        )
      },
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_3_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_3_span_2").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  @Test
  def sequentialFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    runWithTracer { T: Tracer[IO] =>
      def runFiber(fiberNumber: Int): IO[Unit] =
        T.span(s"fiber_${fiberNumber}_span_1").surround {
          runWithSpan(s"fiber_${fiberNumber}_span_2")
        }

      for {
        fiber1 <- runFiber(1).start
        _ <- fiber1.join
        fiber2 <- runFiber(2).start
        _ <- fiber2.join
        fiber3 <- runFiber(3).start
        _ <- fiber3.join
      } yield ()
    }

    testing.waitAndAssertSortedTraces(
      TelemetryDataUtil.orderByRootSpanName(
        "fiber_1_span_1",
        "fiber_1_span_2",
        "fiber_2_span_1",
        "fiber_2_span_2",
        "fiber_3_span_1",
        "fiber_3_span_2"
      ),
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_1_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_1_span_2").hasParent(trace.getSpan(0)))
        )
      },
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_2_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_2_span_2").hasParent(trace.getSpan(0)))
        )
      },
      assertTrace { trace =>
        trace.hasSpansSatisfyingExactly(
          assertSpan(_.hasName("fiber_3_span_1").hasNoParent),
          assertSpan(_.hasName("fiber_3_span_2").hasParent(trace.getSpan(0)))
        )
      }
    )
  }

  private def runWithSpan(
      spanName: String,
      runnable: ThrowingRunnable[Exception] = () => ()
  ): IO[Unit] =
    IO.delay(testing.runWithSpan[Exception](spanName, runnable))

  private def runWithTracer[A](f: Tracer[IO] => IO[A]): A = {
    implicit val provider: LocalProvider[IO, Context] =
      IOLocalContextStorage.localProvider[IO]

    val io =
      for {
        otel4s <- OtelJava.global[IO]
        tracer <- otel4s.tracerProvider.get("test")
        r <- f(tracer)
      } yield r

    io.unsafeRunSync()
  }

  private def assertTrace(f: TraceAssert => Any): Consumer[TraceAssert] =
    (t: TraceAssert) => f(t)

  private def assertSpan(f: SpanDataAssert => Any): Consumer[SpanDataAssert] =
    (t: SpanDataAssert) => f(t)

  //
  // Contex propagation tests
  //

  private val keyProvider = Key.Provider[SyncIO, Context.Key]

  private val key1: Context.Key[String] =
    keyProvider.uniqueKey[String]("key1").unsafeRunSync()

  private val key2: Context.Key[Int] =
    keyProvider.uniqueKey[Int]("key2").unsafeRunSync()

  @Test
  def javaOnlyContextIsProperlyPropagated(): Unit =
    usingModifiedCtx(_.`with`(key1, "1")) {
      assertEquals(Option(jCurrent.get(key1)), Some("1"))
      assertEquals(Option(jCurrent.get(key2)), None)

      usingModifiedCtx(_.`with`(key2, 2)) {
        assertEquals(Option(jCurrent.get(key1)), Some("1"))
        assertEquals(Option(jCurrent.get(key2)), Some(2))

        usingModifiedCtx(_ => JContext.root()) {
          assertEquals(Option(jCurrent.get(key1)), None)
          assertEquals(Option(jCurrent.get(key2)), None)
        }
      }
    }

  @Test
  def fiberOnlyContextIsProperlyPropagated(): Unit =
    withLocalContext { implicit L =>
      doLocally(_.updated(key1, "1")) {
        for {
          _ <- doLocally(_.updated(key2, 2)) {
            for {
              _ <- doScoped(Context.root) {
                for (ctx <- sCurrent) yield {
                  assertEquals(ctx.get(key1), None)
                  assertEquals(ctx.get(key2), None)
                }
              }
              ctx <- sCurrent
            } yield {
              assertEquals(ctx.get(key1), Some("1"))
              assertEquals(ctx.get(key2), Some(2))
            }
          }
          ctx <- sCurrent
        } yield {
          assertEquals(ctx.get(key1), Some("1"))
          assertEquals(ctx.get(key2), None)
        }
      }
    }

  @Test
  def javaContextInsideFiber(): Unit =
    withLocalContext { implicit L =>
      doLocally(_.updated(key1, "1")) {
        for {
          _ <- IO {
            usingModifiedCtx(_.`with`(key2, 2)) {
              val sCtx = sCurrent.unsafeRunSync()
              val jCtx = jCurrent
              assertEquals(sCtx.get(key1), Some("1"))
              assertEquals(sCtx.get(key2), Some(2))
              assertEquals(Option(jCtx.get(key1)), Some("1"))
              assertEquals(Option(jCtx.get(key2)), Some(2))
            }
          }
          sCtx <- sCurrent
          jCtx <- IO(jCurrent)
        } yield {
          assertEquals(sCtx.get(key1), Some("1"))
          assertEquals(sCtx.get(key2), None)
          assertEquals(Option(jCtx.get(key1)), Some("1"))
          assertEquals(Option(jCtx.get(key2)), None)
        }
      }
    }

  @Test
  def javaScalaNested(): Unit =
    withLocalContext { implicit L =>
      IO {
        usingModifiedCtx(_.`with`(key1, "1")) {
          val sCtx = locally {
            for {
              _ <- doLocally(_.updated(key2, 2)) {
                for {
                  sCtx <- sCurrent
                  jCtx <- IO(jCurrent)
                } yield {
                  assertEquals(sCtx.get(key1), Some("1"))
                  assertEquals(sCtx.get(key2), Some(2))
                  assertEquals(Option(jCtx.get(key1)), Some("1"))
                  assertEquals(Option(jCtx.get(key2)), Some(2))
                }
              }
              ctx <- sCurrent
            } yield ctx
          }.unsafeRunSync()
          val jCtx = jCurrent
          assertEquals(sCtx.get(key1), Some("1"))
          assertEquals(sCtx.get(key2), None)
          assertEquals(Option(jCtx.get(key1)), Some("1"))
          assertEquals(Option(jCtx.get(key2)), None)
        }
      }
    }

  @Test
  def lotsOfNesting(): Unit =
    withLocalContext { implicit L =>
      doLocally(_.updated(key1, "1")) {
        for {
          _ <- IO {
            usingModifiedCtx(_.`with`(key2, 2)) {
              usingModifiedCtx(_.`with`(key1, "3")) {
                val sCtx = locally {
                  for {
                    _ <- doLocally(_.updated(key2, 4)) {
                      for {
                        sCtx <- sCurrent
                        jCtx <- IO(jCurrent)
                      } yield {
                        assertEquals(sCtx.get(key1), Some("3"))
                        assertEquals(sCtx.get(key2), Some(4))
                        assertEquals(Option(jCtx.get(key1)), Some("3"))
                        assertEquals(Option(jCtx.get(key2)), Some(4))
                      }
                    }
                    ctx <- sCurrent
                  } yield ctx
                }.unsafeRunSync()
                val jCtx = jCurrent
                assertEquals(sCtx.get(key1), Some("3"))
                assertEquals(sCtx.get(key2), Some(2))
                assertEquals(Option(jCtx.get(key1)), Some("3"))
                assertEquals(Option(jCtx.get(key2)), Some(2))
              }
              val sCtx = locally {
                for {
                  _ <- doScoped(Context.root) {
                    for {
                      sCtx <- sCurrent
                      jCtx <- IO(jCurrent)
                    } yield {
                      assertEquals(sCtx.get(key1), None)
                      assertEquals(sCtx.get(key2), None)
                      assertEquals(Option(jCtx.get(key1)), None)
                      assertEquals(Option(jCtx.get(key2)), None)
                    }
                  }
                  ctx <- sCurrent
                } yield ctx
              }.unsafeRunSync()
              val jCtx = jCurrent
              assertEquals(sCtx.get(key1), Some("1"))
              assertEquals(sCtx.get(key2), Some(2))
              assertEquals(Option(jCtx.get(key1)), Some("1"))
              assertEquals(Option(jCtx.get(key2)), Some(2))
            }
          }
          sCtx <- sCurrent
          jCtx <- IO(jCurrent)
        } yield {
          assertEquals(sCtx.get(key1), Some("1"))
          assertEquals(sCtx.get(key2), None)
          assertEquals(Option(jCtx.get(key1)), Some("1"))
          assertEquals(Option(jCtx.get(key2)), None)
        }
      }
    }

  // `Local`'s methods have their argument lists in an annoying order
  private def doLocally[F[_], A](f: Context => Context)(fa: F[A])(implicit
      L: LocalContext[F]
  ): F[A] =
    L.local(fa)(f)

  private def doScoped[F[_], A](e: Context)(fa: F[A])(implicit
      L: LocalContext[F]
  ): F[A] =
    L.scope(fa)(e)

  private val localProvider: IO[LocalContext[IO]] =
    IOLocalContextStorage.localProvider[IO].local

  private def sCurrent[F[_]](implicit L: LocalContext[F]): F[Context] =
    L.ask[Context]

  private def jCurrent: JContext = JContext.current()

  private def usingModifiedCtx[A](f: JContext => JContext)(body: => A): A =
    Using.resource(f(jCurrent).makeCurrent())(_ => body)

  private def withLocalContext(body: LocalContext[IO] => IO[Any]): Unit = {
    for {
      local <- localProvider
      _ <- body(local)
    } yield ()
  }.unsafeRunSync()

}
