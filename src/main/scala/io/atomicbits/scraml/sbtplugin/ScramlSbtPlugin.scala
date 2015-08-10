package io.atomicbits.scraml.sbtplugin

import sbt._
import Keys._

/**
 * Created by peter on 31/07/15. 
 */
object ScramlSbtPlugin extends AutoPlugin {

  // by defining autoImport, the settings are automatically imported into user's `*.sbt`
  object autoImport {

    // configuration points, like the built-in `version`, `libraryDependencies`, or `compile`
    // override lazy val projectSettings = Seq(commands += helloCommand)
    val scraml = taskKey[Seq[File]]("scraml generator")
    val ramlMain = settingKey[String]("scraml raml file pointer.")

    // default values for the tasks and settings
    lazy val baseScramlSettings: Seq[Def.Setting[_]] = Seq(
      scraml := {
        import _root_.java.nio.file.Files
        import _root_.java.nio.file.Paths
        def generate(ramlPointer: String, dst: File): Seq[File] = {
          if (ramlPointer.nonEmpty) {
            dst.mkdirs()
            val dstFile = dst / "io" / "atomicbits" / "FooBar.scala"
            IO.write(dstFile, """case class FooBar(foo: String, bar: String)""")
            println(s"RAML pointer is nonEmpty: $ramlPointer")
            Seq(dstFile)
          } else {
            println(s"RAML pointer is empty")
            Seq.empty[File]
          }
          //          val sourceFiles = Option(src.list) getOrElse Array() filter (_ endsWith ".txt")
          //          if (sourceFiles.nonEmpty) dst.mkdirs()
          //          for (file <- sourceFiles) yield {
          //            val srcFile = src / file
          //            val dstFile = dst / ((file take (file lastIndexOf '.')) + ".scala")
          //            Files.copy(srcFile.toPath, dstFile.toPath)
          //            dstFile
          //          }
        }
        generate((ramlMain in scraml).value, sourceManaged.value)
      },
      // We can set a default value as below, but to set the value in a project we then need to do:
      // scramlRamlPointer in scraml in Compile := "foo"
      // instead of just:
      // scramlRamlPointer in scraml := "foo"
      // scramlRamlPointer in scraml := "",
      sourceGenerators in Compile += (scraml in Compile).taskValue
    )
  }

  import autoImport._

  override def requires = sbt.plugins.JvmPlugin

  // This plugin is automatically enabled for projects that have a JvmPlugin.
  override def trigger = allRequirements

  // a group of settings that are automatically added to projects.
  override val projectSettings =
    inConfig(Compile)(baseScramlSettings)
  // ++ inConfig(Test)(baseScramlSettings)

}
