package bloop.engine.tasks.compilation

import bloop.data.Project
import bloop.CompileProducts
import bloop.io.AbsolutePath

import scala.collection.mutable

import java.io.File

import xsbti.compile.PreviousResult

case class CompileDependenciesData(
    dependencyClasspath: Array[AbsolutePath],
    dependentResults: Map[File, PreviousResult]
) {
  def buildFullCompileClasspathFor(
      project: Project,
      readOnlyClassesDir: AbsolutePath,
      newClassesDir: AbsolutePath
  ): Array[AbsolutePath] = {
    // Important: always place new classes dir before read-only classes dir
    val classesDirs = Array(newClassesDir, readOnlyClassesDir)
    val projectEntries = project.pickValidResources ++ classesDirs
    projectEntries ++ dependencyClasspath
  }
}

object CompileDependenciesData {
  def compute(
      genericClasspath: Array[AbsolutePath],
      dependentProducts: Map[Project, CompileProducts]
  ): CompileDependenciesData = {
    val resultsMap = new mutable.HashMap[File, PreviousResult]()
    val dependentClassesDir = new mutable.HashMap[AbsolutePath, Array[AbsolutePath]]()
    val dependentResources = new mutable.HashMap[AbsolutePath, Array[AbsolutePath]]()
    dependentProducts.foreach {
      case (project, products) =>
        val genericClassesDir = project.classesDir
        // Important: always place new classes dir before read-only classes dir
        val classesDirs = Array(products.newClassesDir, products.readOnlyClassesDir)
        dependentClassesDir.put(genericClassesDir, classesDirs.map(AbsolutePath(_)))
        dependentResources.put(genericClassesDir, project.pickValidResources)
        resultsMap.put(genericClassesDir.toFile, products.resultForDependentCompilationsInSameRun)
    }

    val rewrittenClasspath = genericClasspath.flatMap { entry =>
      dependentClassesDir.get(entry) match {
        case Some(classesDirs) =>
          dependentResources.get(entry) match {
            case Some(existingResources) =>
              existingResources ++ classesDirs
            case None => classesDirs
          }
        case None => List(entry)
      }
    }

    CompileDependenciesData(rewrittenClasspath, resultsMap.toMap)
  }
}
