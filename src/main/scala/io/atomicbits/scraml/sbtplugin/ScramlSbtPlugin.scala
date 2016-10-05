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
import scala.collection.mutable


/**
  * Created by peter on 31/07/15.
  */
object ScramlSbtPlugin extends AutoPlugin {

  override def buildSettings: Seq[Setting[_]] = autoImport.generateExtraBuildSettings

  val lastModifiedTime = new mutable.HashMap[(Option[String], String), Long] with mutable.SynchronizedMap[(Option[String], String), Long]

  def getLastModifiedTime(ramlDir: Option[String], destination: String): Long = {
    lastModifiedTime.getOrElse((ramlDir, destination), 0L)
  }

  def setLastModifiedTime(ramlDir: Option[String], destination: String, time: Long): Unit = {
    lastModifiedTime += ((ramlDir, destination) -> time)
  }

  val lastGeneratedFiles = new mutable.HashMap[String, Seq[File]] with mutable.SynchronizedMap[String, Seq[File]]

  def getLastGeneratedFiles(destination: String): Seq[File] = lastGeneratedFiles.getOrElse(destination, Seq.empty)

  def setLastGeneratedFiles(destination: String, files: Seq[File]): Unit = {
    lastGeneratedFiles += (destination -> files)
  }


  // by defining autoImport, the settings are automatically imported into user's `*.sbt`
  object autoImport {

    def generateExtraBuildSettings: Seq[Setting[_]] = {

      val version = "0.5.0"

      scramlVersion := version

    }

    val scraml = taskKey[Seq[File]]("scraml generator")
    val scramlRamlApi = settingKey[String]("scraml raml file location")
    val scramlApiPackage = settingKey[String]("scraml package name for the api client class and all its resources")
    val scramlBaseDir = settingKey[String]("scraml base directory")
    val scramlLanguage = settingKey[String]("scraml language setting (defaults to scala)")
    val scramlClasPathResource = settingKey[Boolean]("indicate that raml files are located in a classpath resource (default is false)")
    val scramlLicenseKey = settingKey[String]("scraml 3rd party license key")
    val scramlClassHeader = settingKey[String]("scraml 3rd party class header")
    val scramlVersion = settingKey[String]("scraml version")

    // default values for the tasks and settings
    lazy val baseScramlSettings: Seq[Def.Setting[_]] = Seq(
      scraml := {
        generate(
          (scramlRamlApi in scraml).value,
          (scramlApiPackage in scraml).value,
          resourceDirectory.value,
          (scramlBaseDir in scraml).value,
          (scramlLanguage in scraml).value,
          sourceManaged.value,
          (scramlClasPathResource in scraml).value,
          (scramlLicenseKey in scraml).value,
          (scramlClassHeader in scraml).value
        )
      },
      // We can set a default value as below
      scramlBaseDir in scraml := "",
      scramlLanguage in scraml := "scala",
      scramlRamlApi in scraml := "",
      scramlApiPackage in scraml := "",
      scramlClasPathResource in scraml := false,
      scramlLicenseKey in scraml := "",
      scramlClassHeader in scraml := "",
      // but to set the value in a project we then need to do:
      //   scramlRamlApi in scraml in Compile := "foo"
      // instead of just:
      //   scramlRamlApi in scraml := "foo"
      sourceGenerators in Compile += (scraml in Compile).taskValue,
      // Make sure the generated sources appear in the packaged sources jar as well.
      mappings in(Compile, packageSrc) := {
        val base = (sourceManaged in Compile).value
        val files = (managedSources in Compile).value
        files.map { f =>
          val path: Option[String] = f.relativeTo(base).map(_.getPath)
          (f, path)
        } collect {
          case (file, Some(path)) => (file, path)
        }
      }
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


  private def generate(ramlPointer: String,
                       apiPackage: String,
                       defaultBaseDir: File,
                       givenBaseDir: String,
                       language: String,
                       dst: File,
                       classPathResource: Boolean,
                       scramlLicenseKey: String,
                       scramlClassHeader: String): Seq[File] = {

    if (ramlPointer.nonEmpty) {

      // RAML files are expected to be found in the resource directory if there is no other output directory given.
      val (ramlBaseDir, ramlSource) =
        if (classPathResource) {
          (None, ramlPointer)
        } else {
          val base = if (givenBaseDir.isEmpty) defaultBaseDir else new File(givenBaseDir)
          base.mkdirs()
          val src = new File(base, ramlPointer).toURI.toURL.toString
          (Some(base), src)
        }

      if (classPathResource || needsRegeneration(ramlBaseDir, dst)) {
        println(s"Regenerating $language client from ${ramlBaseDir.map(_.toString).getOrElse("")} to ${dst.toString}")

        val (apiPackageName, apiClassName) = packageAndClassFromRamlPointer(ramlPointer, apiPackage)

        val generatedFiles: Map[String, String] =
          feedbackOnException(
            Try(
              language.toLowerCase match {
                case "java" =>
                  mapAsScalaMap(
                    ScramlGenerator.generateJavaCode(
                      ramlSource,
                      apiPackageName,
                      apiClassName,
                      scramlLicenseKey,
                      scramlClassHeader
                    )
                  ).toMap
                case _      =>
                  mapAsScalaMap(
                    ScramlGenerator.generateScalaCode(
                      ramlSource,
                      apiPackageName,
                      apiClassName,
                      scramlLicenseKey,
                      scramlClassHeader
                    )
                  ).toMap
              }
            ),
            ramlPointer,
            ramlSource
          )

        dst.mkdirs()
        val files: Seq[File] =
          generatedFiles.map {
            case (filePath, content) =>
              val fileInDst = new File(dst, filePath)
              fileInDst.getParentFile.mkdirs()
              IO.write(fileInDst, content)
              fileInDst
          }.toSeq
        setLastGeneratedFiles(dst.toString, files)
        files
      } else {
        println(s"No need for regeneration of $language client for ${ramlBaseDir.map(_.toString).getOrElse("")}")
        getLastGeneratedFiles(dst.toString)
      }

    } else {
      Seq.empty[File]
    }
  }


  private def packageAndClassFromRamlPointer(pointer: String, apiPackage: String): (String, String) = {
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
      val packageName = if (apiPackage.nonEmpty) apiPackage else "io.atomicbits"
      (packageName, cleanFileName(fragments.head))
    } else {
      val packageName = if (apiPackage.nonEmpty) apiPackage else fragments.dropRight(1).mkString(".")
      (packageName, cleanFileName(fragments.takeRight(1).head))
    }

  }


  private def needsRegeneration(ramlDir: Option[File], destination: File): Boolean = {

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

    val topLevelFiles = ramlDir map { dir =>
      if (dir.exists()) dir.listFiles().toList
      else List()
    } getOrElse List()


    val destinationEmpty = !destination.exists() || destination.listFiles().toList.isEmpty

    val changedTime = lastChangedTime(topLevelFiles)

    changedTime.exists { changedT =>
      val changed = changedT > getLastModifiedTime(ramlDir.map(_.toString), destination.toString)
      if (changed) setLastModifiedTime(ramlDir.map(_.toString), destination.toString, changedT)
      // If we have resource files and the destination dir is empty, we regenerate anyhow to avoid starvation after a clean operation.
      changed || destinationEmpty
    }

  }


  private def feedbackOnException(result: Try[Map[String, String]],
                                  ramlPointer: String,
                                  ramlSource: String): Map[String, String] = {
    result match {
      case Success(res)                      => res
      case Failure(ex: NullPointerException) =>
        val ramlSrc = if (ramlSource != null) ramlSource else "null"
        println(
          s"""
             |Exception during RAMl parsing, possibly caused by a wrong RAML path.
             |Are you sure the following values are correct (non-null)?
             |
             |- - - - - - - - - - - - - - - - - - - - - - -
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
