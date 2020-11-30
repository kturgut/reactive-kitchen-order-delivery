name := "ReactiveKitchens"

version := "0.1"
scalaVersion := "2.12.12"

lazy val akkaVersion = "2.6.10"
lazy val leveldbVersion = "0.7"
lazy val leveldbjniVersion = "1.8"

libraryDependencies ++= Seq(
  // "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.lightbend.akka" %% "akka-stream-alpakka-json-streaming" % "2.0.2",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % leveldbjniVersion,
  "io.spray" %% "spray-json" % "1.3.2",
  "org.scalatest" %% "scalatest" % "3.0.5",
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)