import sbt._
import Keys._

import com.socrata.socratasbt.SocrataSbt._
import SocrataSbtKeys._
import com.socrata.socratasbt.CheckClasspath

object BuildSettings {
  val buildSettings: Seq[Setting[_]] =
    Defaults.defaultSettings ++
      socrataBuildSettings ++
      Defaults.itSettings ++
      inConfig(UnitTest)(Defaults.testSettings) ++
      inConfig(ExploratoryTest)(Defaults.testSettings) ++
      Seq(
        scalaVersion := "2.10.0",
        compile in Compile <<= (compile in Compile) dependsOn (CheckClasspath.Keys.failIfConflicts in Compile),
        compile in Test <<= (compile in Test) dependsOn (CheckClasspath.Keys.failIfConflicts in Test),
        testOptions in ExploratoryTest <<= testOptions in Test,
        testOptions in UnitTest <<= (testOptions in Test) map { _ ++ Seq(Tests.Argument("-l", "Slow")) }
      )

  def projectSettings(assembly: Boolean = false): Seq[Setting[_]] =
    BuildSettings.buildSettings ++ socrataProjectSettings(assembly = assembly) ++
      Seq(
        fork in test := true,
        test in Test <<= (test in Test) dependsOn (test in IntegrationTest)
      )

  lazy val projectConfigs = Configurations.default ++ Seq(UnitTest, IntegrationTest, ExploratoryTest)
  lazy val ExploratoryTest = config("explore") extend (Test)
  lazy val UnitTest = config("unit") extend (Test)
}
