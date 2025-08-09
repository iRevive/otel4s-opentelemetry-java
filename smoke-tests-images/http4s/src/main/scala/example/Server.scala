package example

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{TextMapGetter, TextMapSetter}
import org.http4s.{Header, Headers, HttpRoutes, Response}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object Server extends IOApp.Simple {

  private val textMapGetter =
    new TextMapGetter[Headers] {
      def keys(carrier: Headers): java.lang.Iterable[String] =
        carrier.headers.map(_.name.toString).asJava

      def get(carrier: Headers, key: String): String =
        carrier.get(CIString(key)).map(_.head.value).orNull
    }

  private val textMapSetter =
    new TextMapSetter[mutable.Map[String, String]] {
      def set(carrier: mutable.Map[String, String], key: String, value: String): Unit =
        carrier.update(key, value)
    }

  def run: IO[Unit] = {
    val otel = GlobalOpenTelemetry.get()
    val propagators = otel.getPropagators

    def withPropagation[A](headers: Headers)(io: IO[Response[IO]]): IO[Response[IO]] =
      IO.bracketFull { _ =>
        IO {
          val ctx = propagators.getTextMapPropagator.extract(Context.root(), headers, textMapGetter)
          val span = otel.getTracer("app").spanBuilder("request.handler").setParent(ctx).startSpan()
          val scope = span.makeCurrent()
          (scope, span)
        }
      } { _ =>
        for {
          response <- io
          map <- IO(mutable.Map.empty[String, String])
          _ <- IO(propagators.getTextMapPropagator.inject(Context.current(), map, textMapSetter))
          headers <- IO(Headers(map.toSeq.map { case (key, value) => Header.Raw(CIString(key), value): Header.ToRaw }: _*))
        } yield response.withHeaders(headers)
      } { (ctx, _) =>
        IO {
          ctx._1.close()
          ctx._2.end()
        }
      }

    val routes = HttpRoutes.of[IO] {
      case req @ GET -> Root / "welcome" / str =>
        withPropagation(req.headers) {
          for {
            _ <- IO(Span.current().setAttribute("str", str))
            r <- Ok(str)
          } yield r
        }
    }

    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(routes.orNotFound)
      .build
      .evalTap(server => IO.println(s"Started http4s server at ${server.address}"))
      .useForever
  }

}
