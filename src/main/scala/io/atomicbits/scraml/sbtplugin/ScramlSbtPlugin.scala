/*
 *
 * (C) Copyright 2018 Atomic BITS (http://atomicbits.io).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *  Contributors:
 *      Peter Rigole
 *
 */

package io.atomicbits.scraml.sbtplugin

import java.util.concurrent.ConcurrentHashMap
import io.atomicbits.scraml.generator.ScramlGenerator
import sbt._
import Keys._
import scala.util.{ Failure, Success, Try }
import scala.collection.JavaConverters.mapAsScalaMap
import scalariform.BuildInfo

/**
  * Created by peter on 31/07/15.
  */
object ScramlSbtPlugin extends AutoPlugin {

  override def buildSettings: Seq[Setting[_]] = autoImport.generateExtraBuildSettings

  val lastModifiedTime = new ConcurrentHashMap[(Option[String], String), Long]()

  def getLastModifiedTime(ramlDir: Option[String], destination: String): Long = {
    lastModifiedTime.getOrDefault((ramlDir, destination), 0L)
  }

  def setLastModifiedTime(ramlDir: Option[String], destination: String, time: Long): Unit = {
    lastModifiedTime.put((ramlDir, destination), time)
  }

  val lastGeneratedFiles = new ConcurrentHashMap[String, Seq[File]]

  def getLastGeneratedFiles(destination: String): Seq[File] = lastGeneratedFiles.getOrDefault(destination, Seq.empty)

  def setLastGeneratedFiles(destination: String, files: Seq[File]): Unit = {
    lastGeneratedFiles.put(destination, files)
  }

  // by defining autoImport, the settings are automatically imported into user's `*.sbt`
  object autoImport {

    def generateExtraBuildSettings: Seq[Setting[_]] = {

      val version = BuildInfo.version

      scramlVersion := version

    }

    val scraml = taskKey[Seq[File]]("scraml generator")
    val scramlRamlApi = settingKey[String]("scraml raml file location")
    val scramlApiPackage = settingKey[String]("scraml package name for the api client class and all its resources")
    val scramlBaseDir = settingKey[String]("scraml base directory")
    val scramlLanguage = settingKey[String]("scraml language setting, deprecated: use platform instead")
    val scramlPlatform = settingKey[String]("scraml platform setting (defaults to ScalaPlay)")
    val scramlClasPathResource = settingKey[Boolean]("indicate that raml files are located in a classpath resource (default is false)")
    val scramlLicenseKey = settingKey[String]("scraml 3rd party license key (no longer used)")
    val scramlClassHeader = settingKey[String]("scraml 3rd party class header")
    val scramlVersion = settingKey[String]("scraml version")
    val scramlDestinationDir = settingKey[File]("scraml generated source output dir, default is target/scala-<version>/src_managed/")
    val scramlSingleSourceFile = settingKey[String]("scraml single source file name (when all sources should be mapped on a single source file)")

    // default values for the tasks and settings
    lazy val baseScramlSettings: Seq[Def.Setting[_]] = Seq(
      scraml := {
        generate(
          (scraml / scramlRamlApi).value,
          (scraml / scramlApiPackage).value,
          resourceDirectory.value,
          (scraml / scramlBaseDir).value,
          (scraml / scramlLanguage).value,
          (scraml / scramlPlatform).value,
          (scraml / scramlDestinationDir).value,
          (scraml / scramlClasPathResource).value,
          (scraml / scramlLicenseKey).value,
          (scraml / scramlClassHeader).value,
          (scraml / scramlSingleSourceFile).value
        )
      },
      // We can set a default value as below
      scraml / scramlBaseDir := "",
      scraml / scramlLanguage := "",
      scraml / scramlPlatform := "ScalaPlay",
      scraml / scramlRamlApi := "",
      scraml / scramlApiPackage := "",
      scraml / scramlClasPathResource := false,
      scraml / scramlLicenseKey := "",
      scraml / scramlClassHeader := "",
      scraml / scramlDestinationDir := sourceManaged.value,
      scraml / scramlSingleSourceFile := "",
      // but to set the value in a project we then need to do:
      //   Compile / scraml / scramlRamlApi := "foo"
      // instead of just:
      //   scraml / scramlRamlApi := "foo"
      Compile / sourceGenerators += (Compile / scraml).taskValue,
      // Make sure the generated sources appear in the packaged sources jar as well.
      //mappings in(Compile, packageSrc) := {
      Compile / packageSrc / mappings := {
        val base = (Compile / sourceManaged).value
        val files = (Compile / managedSources).value
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
                       platform: String,
                       dst: File,
                       classPathResource: Boolean,
                       scramlLicenseKey: String,
                       scramlClassHeader: String,
                       singleTargetSourceFileName: String): Seq[File] = {

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
              mapAsScalaMap(
                ScramlGenerator.generateScramlCode(
                  getPlatform(platform, language),
                  ramlSource,
                  apiPackageName,
                  apiClassName,
                  scramlLicenseKey,
                  scramlClassHeader,
                  singleTargetSourceFileName
                )
              ).toMap
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
        if(canCompile(getPlatform(platform, language))) {
          files
        } else {
          Seq.empty[File]
        }
      } else {
        println(s"No need for regeneration of $language client for ${ramlBaseDir.map(_.toString).getOrElse("")}")
        if(canCompile(getPlatform(platform, language))) {
          getLastGeneratedFiles(dst.toString)
        } else {
          Seq.empty[File]
        }
      }
    } else {
      Seq.empty[File]
    }
  }

  /**
    * 'language' is deprecated, we should use 'platform' now
    *
    * 'language' defaults to ""
    * 'platform' defaults to "ScalaPlay"
    */
  private def getPlatform(platform: String, language: String): String = {
    val givenPlatform =
      if (language != "") language
      else platform
    givenPlatform match {
      case "scala" => "ScalaPlay"
      case "java"  => "JavaJackson"
      case other   => other
    }
  }

  private def canCompile(platform: String): Boolean = {
    platform.toLowerCase match {
      case "scalaplay" => true
      case "javajackson" => true
      case _ => false
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

  private def feedbackOnException(result: Try[Map[String, String]], ramlPointer: String, ramlSource: String): Map[String, String] = {
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
             |make sure the 'Compile / scraml / scramlRamlApi' value points to the main
             |raml file in your project's (or module's) resources directory.
             |
           """.stripMargin)
        throw ex
      case Failure(ex)                       => throw ex
    }
  }

}
