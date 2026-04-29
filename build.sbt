// PoC: Slick (PR #3544 — slick4 + ActionListener) + otel4s tracing/metrics on MySQL.
//
// Slick is pulled in as an sbt *source dependency* against
// https://github.com/hvesalai/slick.git on branch slick4-tracing,
// which adds the ActionListener[F[_]] hook used in this PoC.
//
// 2.13.18 is the slick4 branch's default scalaVersion, so no `++` is needed
// when building this project.

ThisBuild / scalaVersion := "2.13.18"

lazy val slickGit       = uri("https://github.com/hvesalai/slick.git#slick4-tracing")
lazy val slickRef       = ProjectRef(slickGit, "slick")
// HikariCP support lives in a sibling subproject in slick4.
lazy val slickHikariRef = ProjectRef(slickGit, "hikaricp")

lazy val otel4sVersion       = "0.12.0"
lazy val openTelemetryVersion = "1.51.0"

lazy val root = (project in file("."))
  .dependsOn(slickRef, slickHikariRef)
  .settings(
    name := "slick-otel4s-poc",

    libraryDependencies ++= Seq(
      "org.typelevel"   %% "otel4s-oteljava"                              % otel4sVersion,
      "io.opentelemetry"  % "opentelemetry-sdk-extension-autoconfigure"   % openTelemetryVersion % Runtime,
      "io.opentelemetry"  % "opentelemetry-exporter-logging"              % openTelemetryVersion % Runtime,
      "io.opentelemetry"  % "opentelemetry-exporter-otlp"                 % openTelemetryVersion % Runtime,

      "com.mysql"         % "mysql-connector-j"  % "9.6.0",
      "com.zaxxer"        % "HikariCP"           % "7.0.2",
      "ch.qos.logback"    % "logback-classic"    % "1.5.32"
    ),

    // otel4s-oteljava autoconfigure reads OTEL_* env / -Dotel.* sysprops.
    // Forking is required for the JVM to pick them up before SDK init.
    run / fork        := true,
    run / javaOptions ++= Seq(
      "-Dotel.java.global-autoconfigure.enabled=true",
      "-Dotel.service.name=slick-otel4s-poc",
      // Send to the local OTLP collector (gRPC 4317) and also dump to stdout so
      // we can see what was emitted without leaving the terminal.
      "-Dotel.traces.exporter=otlp,logging",
      "-Dotel.metrics.exporter=otlp,logging",
      "-Dotel.logs.exporter=none",
      "-Dotel.exporter.otlp.endpoint=http://localhost:4317",
      "-Dotel.exporter.otlp.protocol=grpc",
      "-Dotel.metric.export.interval=5000"
    )
  )
