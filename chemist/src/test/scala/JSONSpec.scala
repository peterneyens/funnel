package intelmedia.ws.funnel
package chemist

import org.scalatest.{FlatSpec,Matchers}
import scalaz.\/

class JSONSpec extends FlatSpec with Matchers {
  import JSON._
  import argonaut._, Argonaut._

  it should "foo bar" in {
    Parse.decodeOption[AutoScalingEvent](Fixtures.asgEventJson1) should equal (false)
  }
}
