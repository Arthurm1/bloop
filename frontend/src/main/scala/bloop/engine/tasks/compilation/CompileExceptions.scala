package bloop.engine.tasks.compilation

import scala.util.control.NoStackTrace

private[tasks] object CompileExceptions {
  abstract class CompileException(msg: String) extends RuntimeException(msg) with NoStackTrace
  object FailPromise extends CompileException("Promise completed after compilation error")
  object BlockURI extends CompileException("URI cannot complete: compilation is blocked")
  final case object CompletePromise extends CompileException("Promise completed after compilation")

  case class MissingStoreValue[T](key: String, project: T, caller: T)
      extends CompileException(
        s"Missing key '$key' in store for project ${project} (required by ${caller})."
      )

  case class DuplicatedStoreGet[T](key: String, project: T)
      extends CompileException(
        s"Key '$key' in store for project ${project} was attempted to be computed twice!"
      )
}
