package funnel
package chemist

import knobs.{loadImmutable,Required,FileResource,ClassPathResource}
import java.io.File
import java.net.{ InetSocketAddress, Socket, URI, URL }
import journal.Logger
import oncue.svc.funnel.BuildInfo
import scalaz.{\/,-\/,\/-,Kleisli}
import scalaz.syntax.kleisli._
import scalaz.syntax.traverse._
import scalaz.syntax.id._
import scalaz.std.vector._
import scalaz.std.option._
import scalaz.concurrent.{Task,Strategy}
import scalaz.stream.{Process,Process0, Sink}
import java.util.concurrent.{Executors, ExecutorService, ScheduledExecutorService, ThreadFactory}

trait Chemist[A <: Platform]{
  type ChemistK[U] = Kleisli[Task, A, U]

  private val log = Logger[this.type]

  //////////////////////// PUBLIC API ////////////////////////////

  /**
   * Of all known monitorable services, dispaly the current work assignments
   * of funnel -> flask.
   */
  def distribution: ChemistK[Map[FlaskID, Map[ClusterName, List[URI]]]] =
    config.flatMapK(_.state.distributions.map(Sharding.snapshot))

  /**
   * manually ask chemist to assign the given urls to a flask in order
   * to be monitored. This is not recomended as a daily-use function; chemist
   * should be smart enough to figure out when things go on/offline automatically.
   */
  def distribute(targets: Set[Target]): ChemistK[Unit] =
    Task.now(()).liftKleisli

  /**
   * list all the shards currently known by chemist.
   */
  def shards: ChemistK[Seq[Flask]] =
    config.flatMapK(_.state.distributions.map(Sharding.shards))

  /**
   * display all known node information about a specific shard
   */
  def shard(id: FlaskID): ChemistK[Option[Flask]] =
    shards.map(_.find(_.id == id))

  /**
   * List out the last 100 lifecycle events that this chemist has seen.
   */
  def platformHistory: ChemistK[Seq[PlatformEvent]] =
    config.flatMapK(_.state.events.map(_.filterNot(_ == PlatformEvent.NoOp)))

  /**
   * List the unmonitorable targets.
   */
  def listUnmonitorableTargets: ChemistK[List[Target]] =
    config.flatMapK( cfg =>
      cfg.discovery.listUnmonitorableTargets.map(_.toList.flatMap(_._2)))

  /**
   * Initilize the chemist serivce by trying to create the various AWS resources
   * that are required to operate. Once complete, execute the boostrap.
   */
  def init: ChemistK[Unit]

  //////////////////////// INTERNALS ////////////////////////////

  protected def platform: ChemistK[A] =
    Kleisli.ask[Task, A]

  protected val config: ChemistK[A#Config] =
    platform.map(_.config)
}

object Chemist {
  type Flow[A] = Process[Task,Context[A]]

  case class Context[A](distribution: Sharding.Distribution, value: A)

  /*********************************************************************************/
  /********************************** THREADING ************************************/
  /*********************************************************************************/

  private def daemonThreads(name: String) = new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  }

  val version: String =
    s"Chemist ${BuildInfo.version} (${BuildInfo.gitRevision})"

  val defaultPool: ExecutorService =
    Executors.newFixedThreadPool(4, daemonThreads("chemist-thread"))

  val defaultExecutor: Strategy =
    Strategy.Executor(defaultPool)

  val serverPool: ExecutorService =
    Executors.newCachedThreadPool(daemonThreads("chemist-server"))

  val serverExecutor: Strategy =
    Strategy.Executor(serverPool)

  val schedulingPool: ScheduledExecutorService =
    Executors.newScheduledThreadPool(2, daemonThreads("chemist-scheduled-tasks"))

  def contact(uri: URI): Throwable \/ Unit =
    \/.fromTryCatchThrowable[Unit, Exception]{
      val s = new Socket
      // timeout in 300ms to keep the overhead reasonable
      try s.connect(new InetSocketAddress(uri.getHost, uri.getPort), 1000)
      finally s.close // whatever the outcome, close the socket to prevent leaks.
    }
}

