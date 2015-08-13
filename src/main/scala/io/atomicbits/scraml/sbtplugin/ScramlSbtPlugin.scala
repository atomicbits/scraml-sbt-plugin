package io.atomicbits.scraml.sbtplugin

import io.atomicbits.scraml.generator.ScramlGenerator
import sbt._
import Keys._

/**
 * Created by peter on 31/07/15. 
 */
object ScramlSbtPlugin extends AutoPlugin {

  override def buildSettings: Seq[Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      "io.atomicbits" %% "scraml-dsl" % "0.2.1-SNAPSHOT" withSources() withJavadoc()
    )
  )

  var lastGeneratedFiles: Seq[File] = Seq()

  // by defining autoImport, the settings are automatically imported into user's `*.sbt`
  object autoImport {

    // configuration points, like the built-in `version`, `libraryDependencies`, or `compile`

    // override lazy val projectSettings = Seq(commands += helloCommand)
    val scraml = taskKey[Seq[File]]("scraml generator")
    val scramlRamlApi = settingKey[String]("scraml raml file pointer")

    // default values for the tasks and settings
    lazy val baseScramlSettings: Seq[Def.Setting[_]] = Seq(
      scraml := {
        def generate(ramlPointer: String, dst: File): Seq[File] = {
          // RAML files are expected to be found in the resource directory
          val ramlBaseDir = resourceDirectory.value
          if (ramlPointer.nonEmpty) {
            if (needsRegeneration(ramlBaseDir, dst)) {
              val ramlSource = new File(ramlBaseDir, ramlPointer)
              val (apiPackageName, apiClassName) = packageAndClassFromRamlPointer(ramlPointer)
              val generatedFiles: Seq[(File, String)] =
                ScramlGenerator.generate(s"file://${ramlSource.getCanonicalPath}", apiPackageName, apiClassName)
              dst.mkdirs()
              val files: Seq[File] =
                generatedFiles.map { fileWithContent =>
                  val (file, content) = fileWithContent
                  val fileInDst = new File(dst, file.getCanonicalPath)
                  fileInDst.getParentFile.mkdirs()
                  IO.write(fileInDst, content)
                  fileInDst
                }
              lastGeneratedFiles = files
              files
            } else {
              lastGeneratedFiles
            }
          } else {
            Seq.empty[File]
          }
        }
        generate(
          (scramlRamlApi in scraml).value,
          sourceManaged.value
        )
      },
      // We can set a default value as below
      scramlRamlApi in scraml := "",
      // but to set the value in a project we then need to do:
      //   scramlRamlApi in scraml in Compile := "foo"
      // instead of just:
      //   scramlRamlApi in scraml := "foo"
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

  var lastModifiedTime: Long = 0L

  private def packageAndClassFromRamlPointer(pointer: String): (String, String) = {
    // e.g. io/atomicbits/scraml/api.raml

    def cleanFileName(fileName: String): String = {
      val withOutExtension = fileName.split('.').filter(_.nonEmpty).head
      // capitalize after special characters and drop those characters along the way
      val capitalizedAfterDropChars =
        List('-', '_', '+', ' ').foldLeft(withOutExtension) { (cleaned, dropChar) =>
          cleaned.split(dropChar).filter(_.nonEmpty).map(_.capitalize).mkString("")
        }
      // capitalize after numbers 0 to 9, but keep the numbers
      val capitalized =
        (0 to 9).map(_.toString.head).toList.foldLeft(capitalizedAfterDropChars) { (cleaned, numberChar) =>
          // Make sure we don't drop the occurrences of numberChar at the end by adding a space and removing it later.
          val cleanedWorker = s"$cleaned "
          cleanedWorker.split(numberChar).map(_.capitalize).mkString(numberChar.toString).stripSuffix(" ")
        }
      // final cleanup of all strange characters
      capitalized.replaceAll("[^A-Za-z0-9]", "")
    }

    val fragments = pointer.split('/').toList
    if (fragments.length == 1) {
      ("io.atomicbits", cleanFileName(fragments.head))
    } else {
      (fragments.dropRight(1).mkString("."), cleanFileName(fragments.takeRight(1).head))
    }
  }

  private def needsRegeneration(dir: File, destination: File): Boolean = {

    def lastChangedTime(filesAndDirectories: List[File]): Option[Long] = {

      val (files, directories) = filesAndDirectories.partition(_.isFile)

      val maxFilesModifiedTime =
        if (files.nonEmpty) Some(files.map(_.lastModified()).max)
        else None

      val optLastModifiedTimes = maxFilesModifiedTime :: directories.map(dir => lastChangedTime(dir.listFiles().toList))
      val lastModifiedTimes = optLastModifiedTimes.flatten

      if (lastModifiedTimes.nonEmpty) Some(lastModifiedTimes.max)
      else None
    }

    val topLevelFiles =
      if (dir.exists()) dir.listFiles().toList
      else List()

    val destinationEmpty = !destination.exists() || destination.listFiles().toList.isEmpty

    val changedTime = lastChangedTime(topLevelFiles)

    changedTime.exists { changedT =>
      val changed = changedT > lastModifiedTime
      if (changed) lastModifiedTime = changedT
      // If we have resource files and the destination dir is empty, we regenerate anyhow to avoid starvation after a clean operation.
      changed || destinationEmpty
    }

  }

}
