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


// Publish settings

publishMavenStyle := true

pomIncludeRepository := { _ => false}

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

pomExtra :=  <url>https://github.com/atomicbits/scraml</url>
  <licenses>
    <license>
      <name>AGPL licencse</name>
      <url>http://www.gnu.org/licenses/agpl-3.0.en.html</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:atomicbits/scraml.git</url>
    <connection>scm:git:git@github.com:atomicbits/scraml.git</connection>
  </scm>
  <developers>
    <developer>
      <id>rigolepe</id>
      <name>Peter Rigole</name>
      <url>http://atomicbits.io</url>
    </developer>
  </developers>

credentials ++= (for {
  username <- Option(System.getenv().get("SONATYPE_USERNAME"))
  password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
} yield
  Seq(Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    username,
    password)
  )).getOrElse(Seq())

