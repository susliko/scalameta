package scala.meta.internal
package scalahost

import java.io._
import java.net.URI
import scala.collection.mutable
import scala.compat.Platform.EOL
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent
import scala.util.control.NonFatal
import scala.{meta => m}
import scala.meta.io._
import scala.meta.internal.semantic.DatabaseOps
import scala.meta.internal.semantic.{vfs => v}
import scala.meta.internal.semantic.{schema => s}
import scala.tools.nsc.doc.ScaladocGlobal

trait ScalahostPipeline extends DatabaseOps { self: ScalahostPlugin =>
  // NOTE: Here we encode assumptions that hold by design:
  //   * Output directory stores the semantic db generated by the previous compilation
  //     (that is necessary for incremental compilation support)
  //   * Working directory represents both previous and current compilation roots
  //     (that's the protocol that we assume that Scala build tools support)
  //   * All uris related to the previous semantic db are based on the file: protocol
  //     (that follows from the fact that the output directory is based on the file: protocol)
  lazy val scalametaClasspath = Classpath(
    global.settings.outputDirs.getSingleOutput
      .map(_.file.getAbsolutePath)
      .getOrElse(global.settings.d.value))
  lazy val scalametaTargetroot = scalametaClasspath.shallow.head
  implicit class XtensionURI(uri: URI) { def toFile: File = new File(uri) }

  object ScalahostComponent extends PluginComponent {
    val global: ScalahostPipeline.this.global.type = ScalahostPipeline.this.global
    val runsAfter = List("typer")
    override val runsRightAfter = Some("typer")
    val phaseName = "scalameta"
    override val description = "compute the scala.meta semantic database"
    def newPhase(_prev: Phase) = new ScalahostPhase(_prev)

    class ScalahostPhase(prev: Phase) extends StdPhase(prev) {
      override def apply(unit: g.CompilationUnit): Unit = {
        if (g.isInstanceOf[ScaladocGlobal]) return
        if (config.semanticdb.isDisabled) return

        try {
          if (config.semanticdb.isDisabled || !unit.source.file.name.endsWith(".scala")) return
          val mminidb = m.Database(List(unit.source.toInput -> unit.toAttributes))
          mminidb.save(scalametaTargetroot, config.sourceroot)
        } catch {
          case NonFatal(ex) =>
            val msg = new StringWriter()
            val path = unit.source.file.path
            msg.write(s"failed to generate semanticdb for $path:$EOL")
            ex.printStackTrace(new PrintWriter(msg))
            global.reporter.error(g.NoPosition, msg.toString)
        }
      }

      override def run(): Unit = {
        val vdb = v.Database.load(scalametaClasspath)
        val orphanedVentries = vdb.entries.filter(ventry => {
          val scalaName = v.Paths.semanticdbToScala(ventry.fragment.name)
          !config.sourceroot.resolve(scalaName).isFile
        })
        orphanedVentries.map(ve => {
          def cleanupUpwards(file: File): Unit = {
            if (file.isFile) {
              file.delete()
            } else {
              if (file.getAbsolutePath == ve.base.toString) return
              if (file.listFiles.isEmpty) file.delete()
            }
            cleanupUpwards(file.getParentFile)
          }
          cleanupUpwards(ve.uri.toFile)
        })
        super.run()
      }
    }
  }
}
