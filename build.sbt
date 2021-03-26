import sbt.Compile
// Build shared info
ThisBuild / organization := "co.ledger"
ThisBuild / scalaVersion := "2.13.3"
ThisBuild / resolvers += Resolver.sonatypeRepo("releases")
ThisBuild / scalacOptions ++= CompilerFlags.all

// Dynver settings
ThisBuild / dynverVTagPrefix := false
ThisBuild / dynverSeparator := "-"

// Shared Plugins
enablePlugins(BuildInfoPlugin)
ThisBuild / libraryDependencies += compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

lazy val disableDocGeneration = Seq(
  Compile / doc / sources := Seq.empty,
  Compile / packageDoc / publishArtifact := false
)

lazy val ignoreFiles = List("application.conf.sample")

// Runtime
scalaVersion := "2.13.3"
scalacOptions ++= CompilerFlags.all
resolvers += Resolver.sonatypeRepo("releases")
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](version, git.gitHeadCommit),
  buildInfoPackage := "buildinfo"
)

lazy val dockerSettings = Seq(
  dockerBaseImage := "openjdk:14.0.2",
  dockerRepository := Some("docker.pkg.github.com/ledgerhq/lama"),
  dockerUpdateLatest := true, //should always update latest
  javaAgents += "com.datadoghq" % "dd-java-agent" % "0.76.0"
)

lazy val coverageSettings = Seq(
  coverageMinimum := 0,
  coverageFailOnMinimum := false
)

lazy val sharedSettings =
  dockerSettings ++ Defaults.itSettings ++ coverageSettings ++ disableDocGeneration

lazy val lamaProtobuf = (project in file("protobuf"))
  .enablePlugins(Fs2Grpc)
  .settings(
    name := "lama-protobuf",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Dependencies.commonProtos
  )
  .settings(disableDocGeneration)

// Common lama library
lazy val common = (project in file("common"))
  .configs(IntegrationTest)
  .settings(
    name := "lama-common",
    libraryDependencies ++= (Dependencies.lamaCommon ++ Dependencies.test)
  )
  .settings(disableDocGeneration)
  .dependsOn(lamaProtobuf)

lazy val accountManager = (project in file("account-manager"))
  .enablePlugins(JavaAgent, JavaServerAppPackaging, DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-account-manager",
    sharedSettings,
    libraryDependencies ++= (Dependencies.accountManager ++ Dependencies.test)
  )
  .dependsOn(common)

lazy val bitcoinProtobuf = (project in file("coins/bitcoin/protobuf"))
  .enablePlugins(Fs2Grpc)
  .settings(
    name := "lama-bitcoin-protobuf",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Dependencies.commonProtos,
    PB.protoSources in Compile ++= Seq(
      file("coins/bitcoin/keychain/pb/keychain")
    )
  )
  .settings(disableDocGeneration)

lazy val bitcoinApi = (project in file("coins/bitcoin/api"))
  .enablePlugins(BuildInfoPlugin, JavaAgent, JavaServerAppPackaging, DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-api",
    libraryDependencies ++= (Dependencies.btcApi ++ Dependencies.test),
    sharedSettings,
    buildInfoSettings
  )
  .dependsOn(accountManager, bitcoinCommon, common, bitcoinProtobuf)

lazy val bitcoinCommon = (project in file("coins/bitcoin/common"))
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-common",
    libraryDependencies ++= Dependencies.btcCommon
  )
  .settings(disableDocGeneration)
  .dependsOn(common, bitcoinProtobuf)

lazy val bitcoinWorker = (project in file("coins/bitcoin/worker"))
  .enablePlugins(JavaAgent, JavaServerAppPackaging, DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-worker",
    sharedSettings,
    libraryDependencies ++= (Dependencies.btcWorker ++ Dependencies.test)
  )
  .dependsOn(common, bitcoinCommon)

lazy val bitcoinInterpreter = (project in file("coins/bitcoin/interpreter"))
  .enablePlugins(JavaAgent, JavaServerAppPackaging, DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-interpreter",
    sharedSettings,
    libraryDependencies ++= (Dependencies.btcInterpreter ++ Dependencies.test),
    parallelExecution in IntegrationTest := false
  )
  .dependsOn(common, bitcoinCommon)

lazy val bitcoinTransactor = (project in file("coins/bitcoin/transactor"))
  .enablePlugins(Fs2Grpc, JavaAgent, JavaServerAppPackaging, DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-transactor",
    sharedSettings,
    libraryDependencies ++= (Dependencies.btcCommon ++ Dependencies.commonProtos ++ Dependencies.test),
    // Proto config
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    PB.protoSources in Compile := Seq(
      file("coins/bitcoin/lib-grpc/pb/bitcoin")
    )
  )
  .dependsOn(common, bitcoinCommon)
