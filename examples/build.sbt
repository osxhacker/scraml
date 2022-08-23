scalaVersion := "2.13.8"

val circeVersion = "0.14.2"
val tapirVersion = "1.0.5"

lazy val examples = (project in file("."))
  .settings(
    name := "sbt-scraml-examples",

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),

    libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
    libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
    libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % tapirVersion,
    libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-akka-http-server" % tapirVersion,
    libraryDependencies += "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % "3.3.14",

    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.2.8",

    ramlFile := Some(file("../src/sbt-test/sbt-scraml/simple/api/simple.raml")),
    ramlFieldMatchPolicy := scraml.FieldMatchPolicy.Exact(),
    basePackageName := "scraml.examples",
    librarySupport := Set(scraml.libs.CirceJsonSupport(), scraml.libs.TapirSupport("Endpoints")),
    Compile / sourceGenerators += runScraml
  )
