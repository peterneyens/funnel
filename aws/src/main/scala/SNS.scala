package oncue.svc.funnel.aws

import com.amazonaws.auth.{AWSCredentialsProvider, AWSCredentials}
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.sns.model.{CreateTopicRequest, PublishRequest}
import com.amazonaws.auth.BasicAWSCredentials
import scalaz.concurrent.Task

object SNS {

  def client(
    credentials: BasicAWSCredentials,
    region: Region = Region.getRegion(Regions.fromName("us-east-1"))
  ): AmazonSNSClient = { //cfg.require[String]("aws.region"))
    val client = new AmazonSNSClient()
    client.setRegion(region)
    client
  }

  def publish(regionName: String, payload: String)(client: AmazonSNSClient): Task[Unit] = {
    Task {
      val req = new CreateTopicRequest // cfg.require[String]("aws.snsTopic")
      val res = client.createTopic(req) // idempotent operation
      val arn = res.getTopicArn

      val preq = new PublishRequest(arn, payload)
      val pres = client.publish(preq)
    }
  }
}
