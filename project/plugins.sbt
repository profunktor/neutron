ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

addSbtPlugin("com.github.sbt"        % "sbt-ci-release"             % "1.5.12")
addSbtPlugin("de.heikoseeberger"     % "sbt-header"                 % "5.10.0")
addSbtPlugin("io.spray"              % "sbt-revolver"               % "0.10.0")
addSbtPlugin("org.scalameta"         % "sbt-scalafmt"               % "2.5.2")
addSbtPlugin("org.typelevel"         % "sbt-tpolecat"               % "0.5.0")
addSbtPlugin("com.github.sbt"        % "sbt-ghpages"                % "0.8.0")
addSbtPlugin("org.scalameta"         % "sbt-mdoc"                   % "2.5.4")
addSbtPlugin("com.lightbend.paradox" % "sbt-paradox"                % "0.10.3")
addSbtPlugin("io.github.jonas"       % "sbt-paradox-material-theme" % "0.6.0")
addSbtPlugin("com.typesafe.sbt"      % "sbt-site"                   % "1.3.3")
addSbtPlugin("ch.epfl.scala"         % "sbt-scalafix"               % "0.12.1")
