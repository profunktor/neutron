import Dependencies._

ThisBuild / scalaVersion := "2.13.5"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "dev.profunktor"
ThisBuild / organizationName := "ProfunKtor"
ThisBuild / homepage := Some(url("https://pulsar.profunktor.dev"))
ThisBuild / licenses := List(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / crossScalaVersions := Seq("2.13.5")
ThisBuild / startYear := Some(2021)

ThisBuild / developers := List(
  Developer(
    "gvolpe",
    "Gabriel Volpe",
    "volpegabriel@gmail.com",
    url("https://gvolpe.com")
  )
)

ThisBuild / scalafixDependencies += Libraries.organizeImports

resolvers += Resolver.sonatypeRepo("snapshots")

Compile / run / fork := true
Global / semanticdbEnabled := true

val commonSettings = Seq(
  scalacOptions -= "-Wunused:params", // so many false-positives :(
  scalafmtOnCompile := true,
  autoAPIMappings := true,
  testFrameworks += new TestFramework("weaver.framework.CatsEffect")
)

lazy val noPublish = {
  publish / skip := true
}

lazy val `fs2-pulsar-core` = (project in file("core"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= List(
          CompilerPlugins.kindProjector,
          Libraries.cats,
          Libraries.catsEffect,
          Libraries.fs2,
          Libraries.pulsar,
          Libraries.weaverCats % Test
        )
  )

lazy val `fs2-pulsar-circe` = (project in file("circe"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-pulsar-core`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= List(
          Libraries.avro4s % Provided,
          Libraries.circeCore,
          Libraries.circeParser
        )
  )

lazy val `fs2-pulsar-function` = (project in file("function"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= List(
          Libraries.pulsarFunctionsApi,
          Libraries.java8Compat,
          Libraries.cats             % Test,
          Libraries.catsEffect       % Test,
          Libraries.cats             % Test,
          Libraries.weaverCats       % Test,
          Libraries.weaverScalaCheck % Test
        )
  )

lazy val tests = (project in file("tests"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .configs(IntegrationTest)
  .settings(
    noPublish,
    Defaults.itSettings,
    libraryDependencies ++= List(
          CompilerPlugins.kindProjector,
          Libraries.avro4s       % "it,test",
          Libraries.circeCore    % "it,test",
          Libraries.circeGeneric % "it,test",
          Libraries.circeParser  % "it,test",
          Libraries.weaverCats   % "it,test"
        )
  )
  .dependsOn(`fs2-pulsar-circe`)

lazy val docs = (project in file("docs"))
  .dependsOn(`fs2-pulsar-circe`)
  .enablePlugins(ParadoxSitePlugin)
  .enablePlugins(ParadoxMaterialThemePlugin)
  .enablePlugins(GhpagesPlugin)
  .enablePlugins(MdocPlugin)
  .settings(
    noPublish,
    libraryDependencies ++= List(
          Libraries.avro4s,
          Libraries.circeGeneric
        ),
    scalacOptions -= "-Xfatal-warnings",
    scmInfo := Some(
          ScmInfo(
            url("https://github.com/profunktor/fs2-pulsar"),
            "scm:git:git@github.com:profunktor/fs2-pulsar.git"
          )
        ),
    git.remoteRepo := scmInfo.value.get.connection.replace("scm:git:", ""),
    ghpagesNoJekyll := true,
    version := version.value.takeWhile(_ != '+'),
    paradoxProperties ++= Map(
          "scala-versions" -> (`fs2-pulsar-core` / crossScalaVersions).value
                .map(CrossVersion.partialVersion)
                .flatten
                .map(_._2)
                .mkString("2.", "/", ""),
          "org" -> organization.value,
          "scala.binary.version" -> s"2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
          "fs2-pulsar-core" -> s"${(`fs2-pulsar-core` / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
          "fs2-pulsar-circe" -> s"${(`fs2-pulsar-circe` / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
          "fs2-pulsar-function" -> s"${(`fs2-pulsar-function` / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
          "version" -> version.value
        ),
    mdocIn := (Paradox / sourceDirectory).value,
    mdocExtraArguments ++= Seq("--no-link-hygiene"),
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
    ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox),
    Paradox / paradoxMaterialTheme := {
      ParadoxMaterialTheme()
        .withColor("red", "orange")
        .withLogoIcon("flash_on")
        .withCopyright("Copyright © ProfunKtor")
        .withRepository(uri("https://github.com/profunktor/fs2-pulsar"))
    }
  )

lazy val root = (project in file("."))
  .settings(name := "fs2-pulsar")
  .settings(noPublish)
  .aggregate(
    `fs2-pulsar-function`,
    `fs2-pulsar-circe`,
    `fs2-pulsar-core`,
    docs,
    tests
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports")
