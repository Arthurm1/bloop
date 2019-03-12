package bloop.bsp

import bloop.cli.{ExitStatus, BspProtocol}
import bloop.util.{TestUtil, TestProject}
import bloop.logging.{RecordingLogger, BspClientLogger}
import bloop.internal.build.BuildInfo

import monix.eval.Task
import monix.execution.{Scheduler, ExecutionModel}

import scala.concurrent.duration.FiniteDuration

object TcpBspConnectionSpec extends BspConnectionSpec(BspProtocol.Tcp, true)
object LocalBspConnectionSpec extends BspConnectionSpec(BspProtocol.Local, false)

class BspConnectionSpec(
    override val protocol: BspProtocol,
    override val runOnWindows: Boolean
) extends BspBaseSuite {
  // A custom pool we use to make sure we don't block on threads
  val poolFor6Clients: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(20),
    ExecutionModel.Default
  )

  test("initialize several clients concurrently and simulate a hard disconnection") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "a", Nil)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)

      def createClient: Task[Unit] = {
        Task {
          val logger = new RecordingLogger(ansiCodesSupported = false)
          val bspLogger = new BspClientLogger(logger)
          val bspCommand = createBspCommand(configDir)
          val state = TestUtil.loadTestProject(configDir.underlying, logger)

          // Run the clients on our own unbounded IO scheduler to allow client concurrency
          val scheduler = Some(poolFor6Clients)
          val bspState = openBspConnection(
            state,
            bspCommand,
            configDir,
            bspLogger,
            userIOScheduler = scheduler
          )

          assert(bspState.status == ExitStatus.Ok)
          // Note we opened an unmanaged state and we exit without sending `shutdown`/`exit`
          checkConnectionIsInitialized(logger)

          // Wait 500ms to let the rest of clients to connect to server and then simulate dropout
          Thread.sleep(500)
          bspState.simulateClientDroppingOut()
        }
      }

      val client1 = createClient
      val client2 = createClient
      val client3 = createClient
      val client4 = createClient
      val client5 = createClient
      val client6 = createClient

      // A pool of 20 threads can only support 6 clients concurrently (more will make it fail)
      val firstBatchOfClients = List(client1, client2, client3, client4, client5, client6)
      TestUtil.await(FiniteDuration(5, "s"), poolFor6Clients) {
        Task.gatherUnordered(firstBatchOfClients).map(_ => ())
      }

      val client7 = createClient
      val client8 = createClient
      val client9 = createClient
      val client10 = createClient
      val client11 = createClient
      val client12 = createClient

      // If we can connect six more clients, it means resources were correctly cleaned up
      val secondBatchOfClients = List(client7, client8, client9, client10, client11, client12)
      TestUtil.await(FiniteDuration(5, "s"), poolFor6Clients) {
        Task.gatherUnordered(secondBatchOfClients).map(_ => ())
      }
    }
  }

  def checkConnectionIsInitialized(logger: RecordingLogger): Unit = {
    val jsonrpc = logger.debugs.filter(_.startsWith(" -->"))
    // Filter out the initialize request that contains platform-specific details
    val allButInitializeRequest = jsonrpc.filterNot(_.contains("""build/initialize""""))
    assertNoDiff(
      allButInitializeRequest.mkString(System.lineSeparator),
      s"""| --> {
          |  "result" : {
          |    "displayName" : "${BuildInfo.bloopName}",
          |    "version" : "${BuildInfo.version}",
          |    "bspVersion" : "${BuildInfo.bspVersion}",
          |    "capabilities" : {
          |      "compileProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "testProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "runProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "inverseSourcesProvider" : true,
          |      "dependencySourcesProvider" : true,
          |      "resourcesProvider" : false,
          |      "buildTargetChangedProvider" : false
          |    },
          |    "data" : null
          |  },
          |  "id" : "2",
          |  "jsonrpc" : "2.0"
          |}
          | --> {
          |  "method" : "build/initialized",
          |  "params" : {
          |    
          |  },
          |  "jsonrpc" : "2.0"
          |}""".stripMargin
    )
  }
}
