/*
 *
 *  (C) Copyright 2015 Atomic BITS (http://atomicbits.io).
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the GNU Affero General Public License
 *  (AGPL) version 3.0 which accompanies this distribution, and is available in
 *  the LICENSE file or at http://www.gnu.org/licenses/agpl-3.0.en.html
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  Affero General Public License for more details.
 *
 *  Contributors:
 *      Peter Rigole
 *
 */

package io.atomicbits.scraml.sbtplugin

import io.atomicbits.scraml.generator.ScramlGenerator
import sbt._
import Keys._

import scala.util.{Failure, Success, Try}

import scala.collection.JavaConversions.mapAsScalaMap


/**
 * Created by peter on 31/07/15. 
 */
object ScramlSbtPlugin extends AutoPlugin {

  override def buildSettings: Seq[Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      "io.atomicbits" %% "scraml-dsl-scala" % "0.3.4-SNAPSHOT" withSources() withJavadoc()
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

              val generatedFiles: Map[String, String] =
                feedbackOnException(
                  Try(
                    mapAsScalaMap(ScramlGenerator.generateScalaCode(ramlSource.toURI.toURL.toString, apiPackageName, apiClassName)).toMap
                  ),
                  ramlBaseDir, ramlPointer, ramlSource
                )

              dst.mkdirs()
              val files: Seq[File] =
                generatedFiles.map { filePathsWithContent =>
                  val (filePath, content) = filePathsWithContent
                  val fileInDst = new File(dst, filePath)
                  fileInDst.getParentFile.mkdirs()
                  IO.write(fileInDst, content)
                  fileInDst
                }.toSeq
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


  private def feedbackOnException(result: Try[Map[String, String]],
                                  ramlBaseDir: File,
                                  ramlPointer: String,
                                  ramlSource: File): Map[String, String] = {
    result match {
      case Success(res)                      => res
      case Failure(ex: NullPointerException) =>
        val ramlBase = if (ramlBaseDir != null) ramlBaseDir.getCanonicalPath else "null"
        val ramlSrc = if (ramlSource != null) ramlSource.toURI.toURL.toString else "null"
        println(
          s"""
             |Exception during RAMl parsing, possibly caused by a wrong RAML path.
             |Are you sure the following values are correct (non-null)?
             |
             |- - - - - - - - - - - - - - - - - - - - - - -
             |RAML base path: $ramlBase
              |RAML relative path: $ramlPointer
              |RAML absolute path: $ramlSrc
              |- - - - - - - - - - - - - - - - - - - - - - -
              |
              |In case the relative path is wrong or null, check your project settings and
              |make sure the 'scramlRamlApi in scraml in Compile' value points to the main
              |raml file in your project's (or module's) resources directory.
              |
           """.stripMargin)
        throw ex
      case Failure(ex)                       => throw ex
    }
  }

}
