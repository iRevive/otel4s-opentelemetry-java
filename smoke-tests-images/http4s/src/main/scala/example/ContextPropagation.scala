package example

import cats.effect.{IO, Resource}
import io.netty.util.concurrent.{DefaultEventExecutor, Future => NettyFuture, GenericFutureListener}
import io.opentelemetry.api.trace.Span
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}

import java.util.concurrent.{CompletableFuture, Executors, TimeUnit}

object ContextPropagation {

  private def javaContext: String = {
    val context = Span.current().getSpanContext
    s"${context.getTraceId}/${context.getSpanId}"
  }

  // Reproduces https://github.com/iRevive/otel4s-opentelemetry-java/issues/74.
  // Cold startup exercises Netty's temporary root scope; the warm case isolates listener propagation.
  private def nettyBoundary(warm: Boolean): Resource[IO, IO[String]] =
    Resource.make(IO(new DefaultEventExecutor())) { executor =>
      IO.blocking {
        executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()
        ()
      }
    }.evalTap { executor =>
      if (warm) IO.fromCompletableFuture(IO {
        val started = new CompletableFuture[Unit]()
        executor.execute(new Runnable { def run(): Unit = { started.complete(()); () } })
        started
      }) else IO.unit
    }.map { executor =>
      IO.fromCompletableFuture(IO {
        val completed = new SubscribedFuture[String]()
        val promise = executor.newPromise[Unit]()
        promise.addListener(new GenericFutureListener[NettyFuture[Unit]] {
          def operationComplete(future: NettyFuture[Unit]): Unit = {
            completed.complete(javaContext)
            ()
          }
        })
        executor.execute(new Runnable {
          def run(): Unit = {
            try {
              completed.awaitSubscription()
              // Isolate the listener wrapper from generic executor context propagation.
              val scope = io.opentelemetry.context.Context.root().makeCurrent()
              try promise.setSuccess(())
              finally scope.close()
            } catch {
              case error: Exception => completed.completeExceptionally(error)
            }
            ()
          }
        })
        completed
      })
    }

  private def plainBoundary: Resource[IO, IO[String]] =
    Resource.make(IO(Executors.newSingleThreadExecutor())) { executor =>
      IO.blocking {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        ()
      }
    }.map { executor =>
      IO.fromCompletableFuture(IO {
        val completed = new SubscribedFuture[String]()
        executor.execute(new Runnable {
          def run(): Unit = {
            try {
              completed.awaitSubscription()
              completed.complete(javaContext)
            } catch {
              case error: Exception => completed.completeExceptionally(error)
            }
            ()
          }
        })
        completed
      })
    }

  def observe(boundary: String): IO[String] = {
    implicit val localProvider: LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]
    val resource = boundary match {
      case "netty" => nettyBoundary(warm = false)
      case "netty-warm" => nettyBoundary(warm = true)
      case "plain" => plainBoundary
    }

    for {
      otel <- OtelJava.global[IO]
      tracer <- otel.tracerProvider.get("context-propagation-smoke-test")
      result <- resource.use { crossBoundary =>
        tracer.span(s"$boundary.parent").use { parent =>
          def otel4sContext: IO[String] = tracer.currentSpanContext.map {
            _.map(context => s"${context.traceIdHex}/${context.spanIdHex}").getOrElse("missing")
          }

          for {
            beforeJava <- IO(javaContext)
            beforeOtel4s <- otel4sContext
            callbackJava <- crossBoundary
            afterJava <- IO(javaContext)
            afterOtel4s <- otel4sContext
            _ <- tracer.span(s"$boundary.child").use(_ => IO.unit)
          } yield List(
            s"${parent.context.traceIdHex}/${parent.context.spanIdHex}",
            beforeJava,
            beforeOtel4s,
            callbackJava,
            afterJava,
            afterOtel4s
          ).mkString("\n")
        }
      }
    } yield result
  }
}
