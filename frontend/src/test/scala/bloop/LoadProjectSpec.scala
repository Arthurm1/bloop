package bloop

import java.util.concurrent.TimeUnit

import bloop.config.Config
import bloop.data.Project
import bloop.engine.{BuildLoader, Dag}
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import org.junit.Test

import scala.concurrent.duration.FiniteDuration

class LoadProjectSpec {
  @Test def LoadJavaProject(): Unit = {
    // Make sure that when no scala setup is configured the project load succeeds (and the default instance is used)
    val logger = new RecordingLogger()
    val config0 = Config.File.dummyForTests
    val project = config0.project
    val configWithNoScala = config0.copy(config0.version, project.copy(scala = None))
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val inferredInstance = Project.fromConfig(configWithNoScala, origin, logger).scalaInstance
    assert(inferredInstance.isDefined)
    assert(inferredInstance.get.version.nonEmpty)
  }
}
