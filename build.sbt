name := "scraml-sbt-plugin"

organization := "io.atomicbits"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.10.4"

sbtPlugin := true

scalacOptions := Seq("-deprecation", "-encoding", "utf8")

CrossBuilding.crossSbtVersions := Seq("0.11.5", "0.12", "0.13")

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "2.2.1" % "test" withSources() withJavadoc(),
  "org.scalacheck" %% "scalacheck" % "1.12.1" % "test" withSources() withJavadoc()
)
