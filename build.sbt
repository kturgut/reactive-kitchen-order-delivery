name := "ReactiveKitchens"

version := "0.1"
scalaVersion := "2.13.4"
cancelable in Global := true
lazy val akkaVersion = "2.6.10"
lazy val leveldbjniVersion = "1.8"
scalacOptions ++= Seq("-deprecation")
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  ("com.lightbend.akka" %% "akka-stream-alpakka-json-streaming" % "2.0.2")
    .exclude("com.typesafe.akka", "akka-stream_2.13"),
  "org.fusesource.leveldbjni" % "leveldbjni-all" % leveldbjniVersion,
  "io.spray" %% "spray-json" % "1.3.6",
  "org.scalatest" %% "scalatest" % "3.2.3"
)
