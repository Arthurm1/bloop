package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.engine.{Build, BuildLoader, State}
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.{Logger, RecordingLogger}
import bloop.util.TestUtil
import monix.eval.Task

import bloop.testing.BaseSuite
object BuildLoaderSpec extends BaseSuite {
  testLoad("don't reload if nothing changes") { (testBuild, logger) =>
    testBuild.state.build.checkForChange(logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got ${action}")
    }
  }

  private def configurationFiles(build: TestBuild): List[AbsolutePath] = {
    build.projects.map(p => build.configFileFor(p))
  }

  testLoad("don't reload when configuration files are touched") { (testBuild, logger) =>
    val randomConfigFiles = scala.util.Random.shuffle(configurationFiles(testBuild)).take(5)
    // Update the timestamps of the configuration files to trigger a reload
    randomConfigFiles.foreach(f => Files.write(f.underlying, Files.readAllBytes(f.underlying)))
    testBuild.state.build.checkForChange(logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got ${action}")
    }
  }

  // We add a new project with the bare minimum information
  private val ContentsNewConfigurationFile: String = {
    """
      |{
      |    "version" : "1.0.0",
      |    "project" : {
      |        "name" : "dummy",
      |        "directory" : "/tmp/dummy",
      |        "sources" : [],
      |        "dependencies" : [],
      |        "classpath" : [],
      |        "out" : "/tmp/dummy/target",
      |        "classesDir" : "/tmp/dummy/target/classes"
      |    }
      |}""".stripMargin
  }

  testLoad("reload when new configuration file is added to the build") { (testBuild, logger) =>
    val pathOfDummyFile = testBuild.state.build.origin.resolve("dummy.json").underlying
    Files.write(pathOfDummyFile, ContentsNewConfigurationFile.getBytes(StandardCharsets.UTF_8))

    testBuild.state.build
      .checkForChange(logger)
      .map {
        case action: Build.UpdateState =>
          val hasDummyPath =
            action.createdOrModified.exists(_.origin.path.underlying == pathOfDummyFile)
          if (action.deleted.isEmpty && hasDummyPath) ()
          else sys.error(s"Expected state with new project addition, got ${action}")
        case Build.ReturnPreviousState =>
          sys.error(s"Expected state with new project addition, got ReturnPreviousState")
      }
  }

  testLoad("reload when existing configuration files change") { (testBuild, logger) =>
    val projectsToModify = testBuild.state.build.projects.take(2)
    val backups = projectsToModify.map(p => p -> p.origin.path.readAllBytes)

    val changes = backups.map {
      case (p, bytes) =>
        Task {
          val newContents = (new String(bytes)).replace(p.name, s"${p.name}z")
          Files.write(p.origin.path.underlying, newContents.getBytes(StandardCharsets.UTF_8))
        }
    }

    Task
      .gatherUnordered(changes)
      .flatMap { _ =>
        testBuild.state.build.checkForChange(logger).map {
          case action: Build.UpdateState =>
            val hasAllProjects = {
              val originProjects = projectsToModify.map(_.origin.path).toSet
              action.createdOrModified.map(_.origin.path).toSet == originProjects
            }

            if (action.deleted.isEmpty && hasAllProjects) ()
            else sys.error(s"Expected state modifying ${projectsToModify}, got ${action}")
          case Build.ReturnPreviousState =>
            sys.error(s"Expected state modifying ${projectsToModify}, got ReturnPreviousState")
        }
      }
  }

  testLoad("reload when new configuration file is deleted") { (testBuild, logger) =>
    val configurationFile = testBuild.configFileFor(testBuild.projects.head)
    val change = Task {
      Files.delete(configurationFile.underlying)
    }

    change.flatMap { _ =>
      testBuild.state.build.checkForChange(logger).map {
        case action: Build.UpdateState =>
          val hasProjectDeleted = {
            action.deleted match {
              case List(p) if p == configurationFile => true
              case _ => false
            }
          }

          if (action.createdOrModified.isEmpty && hasProjectDeleted) ()
          else sys.error(s"Expected state with deletion of ${configurationFile}, got ${action}")
        case Build.ReturnPreviousState =>
          sys.error(
            s"Expected state with deletion of ${configurationFile}, got ReturnPreviousState"
          )
      }
    }
  }

  def testLoad[T](name: String)(fun: (TestBuild, RecordingLogger) => Task[T]): Unit = {
    test(name) {
      loadBuildState(fun)
    }
  }

  def loadBuildState[T](f: (TestBuild, RecordingLogger) => Task[T]): T = {
    TestUtil.withinWorkspace { workspace =>
      import bloop.util.TestProject
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val a = TestProject(workspace, "a", Nil)
      val b = TestProject(workspace, "b", Nil)
      val c = TestProject(workspace, "c", Nil)
      val d = TestProject(workspace, "d", Nil)
      val projects = List(a, b, c, d)
      val state = loadState(workspace, projects, logger)
      val configDir = state.build.origin
      val build = TestBuild(state, projects)
      TestUtil.blockOnTask(f(build, logger), 5)
    }
  }
}
