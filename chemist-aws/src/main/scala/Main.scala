package funnel
package chemist
package aws

import scalaz.concurrent.Task
import scalaz.syntax.monad._
import http.MonitoringServer
import journal.Logger
import knobs._

object Main {
  def main(args: Array[String]): Unit = {
    val log = Logger[Main.type]

    val chemist = new AwsChemist[DefaultAws]

    val aws = new DefaultAws {
      val config = (for {
        a <- defaultKnobs
        b <- knobs.aws.config
        c <- knobs.loadImmutable(Required(
          ClassPathResource("oncue/chemist-aws.defaults.cfg")) :: Nil)
        d  = a ++ b ++ c
        _  = log.debug(s"chemist is being configured with knobs: $d")
      } yield AwsConfig.readConfig(d)).run
    }

    val monitoring = MonitoringServer.start(Monitoring.default, 5775)

    // this is the edge of the world and will just block until its stopped
    Server.unsafeStart(new Server(chemist, aws))

    // if we reach these then the server process has stopped and we need
    // to cleanup the associated resources.
    monitoring.stop()
    dispatch.Http.shutdown()
  }
}
