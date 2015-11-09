# scraml-sbt-plugin

[![Build Status](https://travis-ci.org/atomicbits/scraml-sbt-plugin.svg?branch=develop)](https://travis-ci.org/atomicbits/scraml-sbt-plugin)

An SBT plugin for scraml

To enable scraml generation in your project, follow these steps:

Add the following to the plugins.sbt of your Scala project.

    addSbtPlugin("io.atomicbits"      % "scraml-sbt-plugin"   % "0.3.3-SNAPSHOT")
    // We're still on a snapshot release, so the Sonatype snapshot resolver is required
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    
    
In your buld.sbt file add the location of your raml main entry file relatively to your project's 'resources' folder.

    scramlRamlApi in scraml in Compile := "io/atomicbits/scraml/TestClient01.raml"

Compile your project. The generated code will appear in your 'target/scala-2.xx/src_managed folder and it should be picked up as included source code in your project. 

See the https://github.com/atomicbits/scraml-test-scala project for an example. 

## Copyright and License
Copyright 2015 Atomic BITS b.v.b.a. Code released under a dual license philosophy, you can choose which license you use: 

   1. AGPLv3 license (default) 
   2. commercial license (paying) with a free community variant for registered open source projects (needs registration)

