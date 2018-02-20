import CrossBuilding._

name := "scraml-sbt-plugin"

organization := "io.atomicbits"

// Increasing the version needs to be done in 2 places:
// 1. The version of the sbt plugin, just below (version := ...)
// 2. In the ScramlSbtPlugin object, the scraml version variable
version := "0.8.0-M3" // Change in 2 places!

scalaVersion := "2.10.6"

// cross compile using
// ^ compile
// ^ publishLocal
// ^ publishSigned
crossSbtVersions := Vector("0.13.16", "1.1.1")

sbtPlugin := true

scalacOptions := Seq("-deprecation", "-encoding", "utf8")

// Sonatype snapshot resolver is needed to fetch rxhttpclient-scala_2.11:0.2.0-SNAPSHOT and scraml-generator SNAPSHOT
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies ++= Seq(
  "io.atomicbits" %% "scraml-generator" % version.value withSources () withJavadoc ()
)

// Publish settings

publishMavenStyle := true

pomIncludeRepository := { _ =>
  false
}

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

pomExtra := <url>https://github.com/atomicbits/scraml-sbt-plugin</url>
  <licenses>
    <license>
      <name>AGPL license</name>
      <url>http://www.gnu.org/licenses/agpl-3.0.en.html</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:atomicbits/scraml-sbt-plugin.git</url>
    <connection>scm:git:git@github.com:atomicbits/scraml-sbt-plugin.git</connection>
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
} yield Seq(Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password))).getOrElse(Seq())
