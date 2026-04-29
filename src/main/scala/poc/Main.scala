package poc

import cats.effect.{IO, IOApp, Resource}
import com.typesafe.config.ConfigFactory
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import slick.basic.ActionListener
import slick.cats.Database
import slick.dbio.{DBIOAction, NamedAction}
import slick.jdbc.{DatabaseConfig, MySQLProfile}
import slick.jdbc.MySQLProfile.api._
import slick.sql.SqlAction

import scala.concurrent.duration._

object Main extends IOApp.Simple {

  /** Wraps every executed `DBIOAction` node in an otel span. Implementation
   * mirrors the snippet in the PR #3544 description: pull a useful name from
   * `SqlAction` (its rendered SQL via `getDumpInfo.mainInfo`) or `NamedAction`,
   * fall through unchanged for nodes that have nothing interesting to label.
   */
  private def otelListener(tracer: Tracer[IO], meter: Meter[IO]): IO[ActionListener[IO]] =
    meter
      .counter[Long]("slick.dbio.actions")
      .withDescription("Number of Slick DBIO nodes executed.")
      .create
      .map { counter =>
        new ActionListener[IO] {
          override def around[R, H](action: DBIOAction[R, _, _], exec: IO[H]): IO[H] = {
            val maybeName = action match {
              case sql: SqlAction[_, _, _] => Option(sql.getDumpInfo.mainInfo).filter(_.nonEmpty)
              case NamedAction(_, name) => Some(name)
              case _ => None
            }

            // Anonymous Slick action subclasses (notably the `StatementInvoker`
            // built by `sql"...".as[T]`) have an empty `getSimpleName`. The
            // `getSuperclass` is `Object` since their concrete supertypes are
            // mixed-in traits, so look at the action's type instead.
            val kind = {
              val s = action.getClass.getSimpleName
              if (s.nonEmpty) s
              else action match {
                case _: SqlAction[_, _, _] => "SqlAction"
                case _                     => action.getClass.getName
              }
            }

            maybeName match {
              case Some(name) =>
                tracer
                  .spanBuilder("slick.dbio")
                  .addAttribute(Attribute("action", name))
                  .addAttribute(Attribute("action.kind", kind))
                  .build
                  .surround(exec)
                  .productL(counter.inc(Attribute("action.kind", kind)))
              case None =>
                exec.productL(counter.inc(Attribute("action.kind", kind)))
            }
          }
        }
      }

  /** Open a MySQL-backed Slick database whose interpreter is instrumented by
   * `listener`. Uses the "advanced opening path" introduced in PR #3544:
   * `Database.fromCore(makeDatabase(config, listener))`.
   */
  private def databaseResource(listener: ActionListener[IO]): Resource[IO, Database] = {
    val cfg = ConfigFactory.parseString(
      """
        |mydb {
        |  profile = "slick.jdbc.MySQLProfile$"
        |  db {
        |    connectionPool = "HikariCP"
        |    dataSourceClass = "com.mysql.cj.jdbc.MysqlDataSource"
        |    properties {
        |      serverName = "127.0.0.1"
        |      portNumber = "3306"
        |      databaseName = "mysql"
        |      user = "root"
        |      password = "root"
        |    }
        |    maxConnections = 4
        |  }
        |}
        |""".stripMargin
    )
    val dc = DatabaseConfig.forConfig[MySQLProfile]("mydb", cfg)

    Resource.fromAutoCloseable(
      dc.profile.backend.makeDatabase[IO](dc, listener).map(Database.fromCore)
    )
  }

  override def run: IO[Unit] =
    OtelJava.autoConfigured[IO]().use { otel =>
      for {
        tracer <- otel.tracerProvider.get("slick-otel4s-poc")
        meter <- otel.meterProvider.get("slick-otel4s-poc")
        listener <- otelListener(tracer, meter)
        _ <- databaseResource(listener).use { db =>
          tracer.span("poc.workflow").surround(
            for {
              now <- db.run(sql"SELECT NOW()".as[String].head.named("select-now"))
              answer <- db.run(sql"SELECT 41 + 1".as[Int].head.named("select-answer"))
              _ <- IO.println(s"NOW = $now, answer = $answer")

              // A streaming DBIOAction backed by a recursive CTE. Each row
              // calls SLEEP(0.4) server-side so the whole stream takes ~2s,
              // which makes the wrapping `slick.dbio` span easy to spot in
              // Tempo / any trace viewer.
              slowStream =
                sql"""
                  WITH RECURSIVE seq(n) AS (
                    SELECT 1
                    UNION ALL
                    SELECT n + 1 FROM seq WHERE n < 5
                  )
                  SELECT CONCAT('row-', n, ' (server slept 0.4s, returned ', SLEEP(0.4), ')') FROM seq
                """.as[String]
              _ <- IO.println("Streaming slow rows (each row server-sleeps 0.4s)...")
              _ <- db
                     .stream(slowStream)
                     .evalTap(row => IO.println(s"  $row"))
                     .compile
                     .drain
            } yield ()
          )
        }
        _ <- IO.sleep(10.seconds) // wait a bit before exiting so we can see the metrics in the local OTLP collector
      } yield ()
    }
}
