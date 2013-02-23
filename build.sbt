name := "ProxyActor"

version := "0.9"

organization := "API Technologies, LLC"

scalaVersion := "2.10.0"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

seq(ScctPlugin.instrumentSettings : _*)

libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.10.0"

libraryDependencies += "cglib" % "cglib-nodep" % "2.2.2"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.0.M5b" % "test"

libraryDependencies +=
    "org.scalamock" %% "scalamock-scalatest-support" % "3.0.1" % "test"