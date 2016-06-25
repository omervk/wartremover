import ReleaseTransformations._
import com.typesafe.sbt.pgp.PgpKeys._

lazy val commonSettings = Seq(
  organization := "org.wartremover",
  licenses := Seq(
    "The Apache Software License, Version 2.0" ->
      url("http://www.apache.org/licenses/LICENSE-2.0.txt")
  ),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  publishTo <<= version { (v: String) =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  homepage := Some(url("http://wartremover.org")),
  pomExtra :=
    <scm>
      <url>git@github.com:puffnfresh/wartremover.git</url>
      <connection>scm:git:git@github.com:puffnfresh/wartremover.git</connection>
    </scm>
    <developers>
      <developer>
        <id>puffnfresh</id>
        <name>Brian McKenna</name>
        <url>http://brianmckenna.org/</url>
      </developer>
    </developers>
)

lazy val root = Project(
  id = "root",
  base = file("."),
  aggregate = Seq(core)
).settings(commonSettings ++ Seq(
  publishArtifact := false,
  crossScalaVersions := Seq("2.11.7", "2.10.6"),
  crossVersion := CrossVersion.binary,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts.copy(action = st => { // publish *everything* with `sbt "release cross"`
    val extracted = Project.extract(st)
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(publishSigned in Global in ref, st)
    }),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
): _*)

lazy val core = Project(
  id = "core",
  base = file("core"),
  aggregate = Seq(sbtPlug)
).settings(commonSettings ++ Seq(
  name := "wartremover",
  scalaVersion := "2.11.7",
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full),
  libraryDependencies := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) =>
        libraryDependencies.value :+ ("org.scalamacros" %% "quasiquotes" % "2.0.1")
      case _ =>
        libraryDependencies.value
    }
  },
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    "org.scalatest" %% "scalatest" % "2.2.1" % "test"
  ),
  // a hack (?) to make `compile` and `+compile` tasks etc. behave sanely
  aggregate := CrossVersion.partialVersion((scalaVersion in Global).value) != Some((2, 11))
): _*)

lazy val sbtPlug: Project = Project(
  id = "sbt-plugin",
  base = file("sbt-plugin")
).settings(commonSettings ++ Seq(
  sbtPlugin := true,
  name := "sbt-wartremover",
  sourceGenerators in Compile <+= Def.task {
    val base = (sourceManaged in Compile).value
    val file = base / "wartremover" / "Wart.scala"
    val wartsDir = core.base / "src" / "main" / "scala" / "wartremover" / "warts"
    val warts: Seq[String] = wartsDir.listFiles.toSeq.map(_.getName.replaceAll("""\.scala$""", "")).
      filterNot(Seq("Unsafe", "ForbidInference") contains _).sorted
    val unsafe = warts.filter(IO.read(wartsDir / "Unsafe.scala") contains _)
    val content =
      s"""package wartremover
         |// Autogenerated code, see build.sbt.
         |final class Wart private[wartremover](val clazz: String)
         |object Wart {
         |  private[wartremover] val PluginVersion = "${version.value}"
         |  private[wartremover] lazy val AllWarts = List(${warts mkString ", "})
         |  private[wartremover] lazy val UnsafeWarts = List(${unsafe mkString ", "})
         |  /** A fully-qualified class name of a custom Wart implementing `org.wartremover.WartTraverser`. */
         |  def custom(clazz: String): Wart = new Wart(clazz)
         |  private[this] def w(nm: String): Wart = new Wart(s"org.wartremover.warts.$$nm")
         |""".stripMargin +
        warts.map(w => s"""  val $w = w("${w}")""").mkString("\n") + "\n}\n"
    IO.write(file, content)
    Seq(file)
  }
): _*)
