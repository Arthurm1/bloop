package bloop.integrations.sbt

import bloop.config.Config
import sbt.{Def, Artifact, Keys, SettingKey}
import java.io.File

object Compat {
  type PluginData = sbt.PluginData
  val PluginData = sbt.PluginData
  val PluginDiscovery = sbt.PluginDiscovery
  val PluginManagement = sbt.PluginManagement

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)

  def currentCommandFromState(s: sbt.State): Option[String] = Some(s.history.current)

  def generateCacheFile(s: sbt.Keys.TaskStreams, id: String) = s.cacheDirectory / id

  def toBloopArtifact(a: Artifact, f: File): Config.Artifact = {
    Config.Artifact(a.name, a.classifier, None, f.toPath)
  }

  def toAnyRefSettingKey(id: String, m: Manifest[AnyRef]): SettingKey[AnyRef] = SettingKey(id)(m)

  lazy val compileSettings: Seq[Def.Setting[_]] = Nil
}
