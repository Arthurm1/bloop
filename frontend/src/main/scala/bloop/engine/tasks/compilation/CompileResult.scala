package bloop.engine.tasks.compilation

import java.util.concurrent.CompletableFuture

import bloop.{Compiler, JavaSignal, CompileProducts}
import bloop.data.Project
import bloop.reporter.Problem
import bloop.util.CacheHashCode

import monix.eval.Task
import monix.execution.CancelableFuture

import xsbti.compile.{EmptyIRStore, IRStore}

import scala.util.Try

sealed trait CompileResult[+R] {
  def result: R
}

sealed trait PartialCompileResult extends CompileResult[Task[ResultBundle]] {
  def result: Task[ResultBundle]
  def store: IRStore
}

object PartialCompileResult {
  def apply(
      bundle: CompileBundle,
      store: Try[IRStore],
      completeJava: CompletableFuture[Unit],
      javaTrigger: Task[JavaSignal],
      result: Task[ResultBundle]
  ): PartialCompileResult = {
    store match {
      case scala.util.Success(store) =>
        PartialSuccess(bundle, store, completeJava, javaTrigger, result)
      case scala.util.Failure(CompileExceptions.CompletePromise(store)) =>
        PartialSuccess(bundle, store, completeJava, javaTrigger, result)
      case scala.util.Failure(t) =>
        PartialFailure(bundle.project, t, result)
    }
  }

  /**
   * Turns a partial compile result to a full one. In the case of normal
   * compilation, this is an instant operation since the task returning the
   * results is already completed. In the case of pipelined compilation, this
   * is not the case, so that's why the operation returns a task.
   */
  def toFinalResult(result: PartialCompileResult): Task[List[FinalCompileResult]] = {
    result match {
      case PartialEmpty => Task.now(FinalEmptyResult :: Nil)
      case f @ PartialFailure(project, _, bundle) =>
        bundle.map(b => FinalNormalCompileResult(project, b, f.store) :: Nil)
      case PartialFailures(failures, _) =>
        Task.gatherUnordered(failures.map(toFinalResult(_))).map(_.flatten)
      case PartialSuccess(bundle, store, _, _, result) =>
        result.map(res => FinalNormalCompileResult(bundle.project, res, store) :: Nil)
    }
  }
}

case object PartialEmpty extends PartialCompileResult {
  override final val result: Task[ResultBundle] =
    Task.now(ResultBundle(Compiler.Result.Empty, None, CancelableFuture.unit))
  override def store: IRStore = EmptyIRStore.getStore()
}

case class PartialFailure(
    project: Project,
    exception: Throwable,
    result: Task[ResultBundle]
) extends PartialCompileResult
    with CacheHashCode {
  def store: IRStore = EmptyIRStore.getStore()
}

case class PartialFailures(
    failures: List[PartialCompileResult],
    result: Task[ResultBundle]
) extends PartialCompileResult
    with CacheHashCode {
  override def store: IRStore = EmptyIRStore.getStore()
}

case class PartialSuccess(
    bundle: CompileBundle,
    store: IRStore,
    completeJava: CompletableFuture[Unit],
    javaTrigger: Task[JavaSignal],
    result: Task[ResultBundle]
) extends PartialCompileResult
    with CacheHashCode

sealed trait FinalCompileResult extends CompileResult[ResultBundle] {
  def store: IRStore
  def result: ResultBundle
}

case object FinalEmptyResult extends FinalCompileResult {
  override final val store: IRStore = EmptyIRStore.getStore()
  override final val result: ResultBundle = ResultBundle.empty
}

case class FinalNormalCompileResult private (
    project: Project,
    result: ResultBundle,
    store: IRStore
) extends FinalCompileResult
    with CacheHashCode

object FinalNormalCompileResult {
  object HasException {
    def unapply(res: FinalNormalCompileResult): Option[(Project, Throwable)] = {
      res.result.fromCompiler match {
        case Compiler.Result.Failed(_, Some(t), _, _) =>
          Some((res.project, t))
        case _ => None
      }
    }
  }
}

object FinalCompileResult {
  import scalaz.Show
  final implicit val showFinalResult: Show[FinalCompileResult] = new Show[FinalCompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: FinalCompileResult): String = {
      r match {
        case FinalEmptyResult => s"<empty> (product of dag aggregation)"
        case FinalNormalCompileResult(project, result, _) =>
          val projectName = project.name
          result.fromCompiler match {
            case Compiler.Result.Empty => s"${projectName} (empty)"
            case Compiler.Result.Cancelled(problems, ms, _) =>
              s"${projectName} (cancelled, failed with ${Problem.count(problems)}, ${ms}ms)"
            case Compiler.Result.Success(_, _, _, ms, _, isNoOp) =>
              val noOp = if (isNoOp) " no-op" else ""
              s"${projectName} (success$noOp ${ms}ms)"
            case Compiler.Result.Blocked(on) => s"${projectName} (blocked on ${on.mkString(", ")})"
            case Compiler.Result.GlobalError(problem) =>
              s"${projectName} (failed with global error ${problem})"
            case Compiler.Result.Failed(problems, t, ms, _) =>
              val extra = t match {
                case Some(t) => s"exception '${t.getMessage}', "
                case None => ""
              }
              s"${projectName} (failed with ${Problem.count(problems)}, $extra${ms}ms)"
          }
      }
    }
  }
}
