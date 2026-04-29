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
import slick.jdbc.MySQLProfile.api.*
import slick.sql.SqlAction

object Main extends IOApp.Simple:

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
        new ActionListener[IO]:
          override def around[R, H](action: DBIOAction[R, ?, ?], exec: IO[H]): IO[H] =
            val maybeName = action match
              case sql: SqlAction[?, ?, ?] => Option(sql.getDumpInfo.mainInfo).filter(_.nonEmpty)
              case NamedAction(_, name)    => Some(name)
              case _                       => None

            val kind = action.getClass.getSimpleName

            maybeName match
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

  /** Open a MySQL-backed Slick database whose interpreter is instrumented by
    * `listener`. Uses the "advanced opening path" introduced in PR #3544:
    * `Database.fromCore(makeDatabase(config, listener))`.
    */
  private def databaseResource(listener: ActionListener[IO]): Resource[IO, Database] =
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

    Resource.make(
      dc.profile.backend
        .makeDatabase[IO](dc, listener)
        .map(Database.fromCore)
    )(db => IO.blocking(db.close()).attempt.void)

  override def run: IO[Unit] =
    OtelJava.autoConfigured[IO]().use { otel =>
      for
        tracer   <- otel.tracerProvider.get("slick-otel4s-poc")
        meter    <- otel.meterProvider.get("slick-otel4s-poc")
        listener <- otelListener(tracer, meter)
        _        <- databaseResource(listener).use { db =>
          tracer.span("poc.workflow").surround(
            for
              now    <- db.run(sql"SELECT NOW()".as[String].head.named("select-now"))
              answer <- db.run(sql"SELECT 41 + 1".as[Int].head.named("select-answer"))
              _      <- IO.println(s"NOW = $now, answer = $answer")
            yield ()
          )
        }
      yield ()
    }

end Main
