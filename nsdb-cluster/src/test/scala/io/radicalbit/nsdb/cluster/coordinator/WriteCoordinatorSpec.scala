package io.radicalbit.nsdb.cluster.coordinator

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import io.radicalbit.commit_log.CommitLogService.{Delete, Insert}
import io.radicalbit.nsdb.actors.PublisherActor.Command.SubscribeBySqlStatement
import io.radicalbit.nsdb.actors.PublisherActor.Events.{RecordsPublished, SubscribedByQueryString}
import io.radicalbit.nsdb.actors._
import io.radicalbit.nsdb.cluster.actor.NamespaceDataActor
import io.radicalbit.nsdb.commit_log.CommitLogWriterActor.WroteToCommitLogAck
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.common.statement._
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

class TestCommitLogService extends Actor {
  def receive = {
    case Insert(ts, metric, record) =>
      sender() ! WroteToCommitLogAck(ts, metric, record)
    case Delete(_, _) => sys.error("Not Implemented")
  }
}

class TestSubscriber extends Actor {
  var receivedMessages = 0
  def receive = {
    case RecordsPublished(_, _, _) =>
      receivedMessages += 1
  }
}

class FakeReadCoordinatorActor extends Actor {
  def receive: Receive = {
    case ExecuteStatement(_) =>
      sender() ! SelectStatementExecuted(db = "db",
                                         namespace = "testNamespace",
                                         metric = "testMetric",
                                         values = Seq.empty)
  }
}

class WriteCoordinatorSpec
    extends TestKit(ActorSystem("nsdb-test"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  val probe                = TestProbe()
  val probeActor           = probe.ref
  val namespaceSchemaActor = TestActorRef[NamespaceSchemaActor](NamespaceSchemaActor.props("target/test_index"))
  val namespaceDataActor   = TestActorRef[NamespaceDataActor](NamespaceDataActor.props("target/test_index"))
  val subscriber           = TestActorRef[TestSubscriber](Props[TestSubscriber])
  val publisherActor = TestActorRef[PublisherActor](
    PublisherActor.props("target/test_index", system.actorOf(Props[FakeReadCoordinatorActor])))
  val writeCoordinatorActor = system actorOf WriteCoordinator.props(namespaceSchemaActor,
                                                                    Some(system.actorOf(Props[TestCommitLogService])),
                                                                    namespaceDataActor,
                                                                    publisherActor)

  "WriteCoordinator" should "write records" in {
    val record1 = Bit(System.currentTimeMillis, 1, Map("content" -> s"content"))
    val record2 = Bit(System.currentTimeMillis, 2, Map("content" -> s"content", "content2" -> s"content2"))
    val incompatibleRecord =
      Bit(System.currentTimeMillis, 3, Map("content" -> 1, "content2" -> s"content2"))

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "db", "testNamespace", "testMetric", record1))

    val expectedAdd = probe.expectMsgType[InputMapped]
    expectedAdd.metric shouldBe "testMetric"
    expectedAdd.record shouldBe record1

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "db", "testNamespace", "testMetric", record2))

    val expectedAdd2 = probe.expectMsgType[InputMapped]
    expectedAdd2.metric shouldBe "testMetric"
    expectedAdd2.record shouldBe record2

    probe.send(writeCoordinatorActor,
               MapInput(System.currentTimeMillis, "db", "testNamespace", "testMetric", incompatibleRecord))

    probe.expectMsgType[RecordRejected]

  }

  "WriteCoordinator" should "write records and publish event to its subscriber" in {
    val testRecordSatisfy = Bit(100, 1, Map("name" -> "john"))

    val testSqlStatement = SelectSQLStatement(
      db = "db",
      namespace = "registry",
      metric = "testMetric",
      fields = AllFields,
      condition = Some(
        Condition(ComparisonExpression(dimension = "timestamp", comparison = GreaterOrEqualToOperator, value = 10L))),
      limit = Some(LimitOperator(4))
    )

    probe.send(publisherActor, SubscribeBySqlStatement(subscriber, "testQueryString", testSqlStatement))
    probe.expectMsgType[SubscribedByQueryString]
    publisherActor.underlyingActor.subscribedActors.keys.size shouldBe 1
    publisherActor.underlyingActor.queries.keys.size shouldBe 1

    probe.send(writeCoordinatorActor,
               MapInput(System.currentTimeMillis, "db", "testNamespace", "testMetric", testRecordSatisfy))

    within(5 seconds) {
      val expectedAdd = probe.expectMsgType[InputMapped]
      expectedAdd.metric shouldBe "testMetric"
      expectedAdd.record shouldBe testRecordSatisfy

      subscriber.underlyingActor.receivedMessages shouldBe 1
    }
  }

  "WriteCoordinator" should "delete a namespace" in {
    probe.send(writeCoordinatorActor, DeleteNamespace("db", "testNamespace"))

    within(5 seconds) {
      probe.expectMsgType[NamespaceDeleted]

      namespaceDataActor.underlyingActor.indexerActors.keys.size shouldBe 0
      namespaceSchemaActor.underlyingActor.schemaActors.keys.size shouldBe 0
    }
  }

  "WriteCoordinator" should "delete entries" in {

    val records: Seq[Bit] = Seq(
      Bit(2, 1, Map("name"  -> "John", "surname"  -> "Doe", "creationDate" -> System.currentTimeMillis())),
      Bit(4, 1, Map("name"  -> "John", "surname"  -> "Doe", "creationDate" -> System.currentTimeMillis())),
      Bit(6, 1, Map("name"  -> "Bill", "surname"  -> "Doe", "creationDate" -> System.currentTimeMillis())),
      Bit(8, 1, Map("name"  -> "Frank", "surname" -> "Doe", "creationDate" -> System.currentTimeMillis())),
      Bit(10, 1, Map("name" -> "Frank", "surname" -> "Doe", "creationDate" -> System.currentTimeMillis()))
    )

    records.foreach(r =>
      probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "db", "testDelete", "testMetric", r)))

    within(5 seconds) {
      (0 to 4) foreach { _ =>
        probe.expectMsgType[InputMapped]
      }
    }

    probe.send(
      writeCoordinatorActor,
      ExecuteDeleteStatement(
        DeleteSQLStatement(
          db = "db",
          namespace = "testDelete",
          metric = "testMetric",
          condition = Condition(RangeExpression(dimension = "timestamp", value1 = 2L, value2 = 4L))
        )
      )
    )
    within(5 seconds) {
      probe.expectMsgType[DeleteStatementExecuted]
    }
  }

  "WriteCoordinator" should "drop a metric" in {
    probe.send(writeCoordinatorActor, DropMetric("db", "testNamespace", "testMetric"))
    within(5 seconds) {
      probe.expectMsgType[MetricDropped]
    }
  }

}