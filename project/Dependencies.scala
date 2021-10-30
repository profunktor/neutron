import sbt._

object Dependencies {

  object V {
    val avro4s_2   = "4.0.11"
    val avro4s_3   = "5.0.0.M1"
    val cats       = "2.6.1"
    val catsEffect = "3.2.9"
    val circe      = "0.14.1"
    val fs2        = "3.1.6"
    val pulsar     = "2.8.0"
    val weaver     = "0.7.6"

    val kindProjector   = "0.13.2"
    val organizeImports = "0.5.0"
  }

  object Libraries {
    val cats       = "org.typelevel" %% "cats-core"   % V.cats
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val fs2        = "co.fs2"        %% "fs2-core"    % V.fs2

    val circeCore    = "io.circe" %% "circe-core"    % V.circe
    val circeGeneric = "io.circe" %% "circe-generic" % V.circe
    val circeParser  = "io.circe" %% "circe-parser"  % V.circe

    val avro4s        = "com.sksamuel.avro4s" %% "avro4s-core" % V.avro4s_2
    val avro4s_scala3 = "com.sksamuel.avro4s" %% "avro4s-core" % V.avro4s_3

    val pulsar             = "org.apache.pulsar" % "pulsar-client"        % V.pulsar
    val pulsarFunctionsApi = "org.apache.pulsar" % "pulsar-functions-api" % V.pulsar

    // Testing
    val weaverCats       = "com.disneystreaming" %% "weaver-cats"       % V.weaver
    val weaverScalaCheck = "com.disneystreaming" %% "weaver-scalacheck" % V.weaver

    // Scalafix rules
    val organizeImports = "com.github.liancheng" %% "organize-imports" % V.organizeImports
  }

  object CompilerPlugins {
    val kindProjector = compilerPlugin(
      "org.typelevel" %% "kind-projector" % V.kindProjector cross CrossVersion.full
    )
  }

}
