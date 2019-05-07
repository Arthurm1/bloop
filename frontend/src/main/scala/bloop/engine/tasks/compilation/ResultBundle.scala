package bloop.engine.tasks.compilation

import bloop.Compiler
import bloop.engine.caches.LastSuccessfulResult

import monix.execution.CancelableFuture

case class ResultBundle(
    fromCompiler: Compiler.Result,
    successful: Option[LastSuccessfulResult],
    runningBackgroundTasks: CancelableFuture[Unit]
)

object ResultBundle {
  val empty: ResultBundle =
    ResultBundle(Compiler.Result.Empty, None, CancelableFuture.successful(()))
}
