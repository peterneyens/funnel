package oncue.svc.funnel
package agent
package jmx

import scalaz.\/, \/._
import scalaz.syntax.std.either._
import org.scalatest._

class ParserSpec extends FlatSpec with Matchers {
  it should "parse zookeeper bean names" in {
    Parser.fromCanonicalName("org.apache.ZooKeeperService:name0=StandaloneServer_port-1,name1=InMemoryDataTree"
      ) should equal (right("org/apache/zookeeperservice/standaloneserver_port-1/inmemorydatatree"))
  }

  it should "parse cassandra bean names" in {
    Parser.fromCanonicalName("org.apache.cassandra.db:columnfamily=schema_columns,keyspace=system,type=ColumnFamilies"
      ) should equal (right("org/apache/cassandra/db/schema_columns/system/columnfamilies"))
  }
}
