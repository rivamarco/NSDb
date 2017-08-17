package io.radicalbit.nsdb.coordinator

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import io.radicalbit.commit_log.CommitLogService.{Delete, Insert}
import io.radicalbit.nsdb.actors.IndexerActor.RecordRejected
import io.radicalbit.nsdb.actors.{IndexerActor, SchemaActor}
import io.radicalbit.nsdb.commit_log.CommitLogWriterActor.WroteToCommitLogAck
import io.radicalbit.nsdb.coordinator.WriteCoordinator.{InputMapped, MapInput}
import io.radicalbit.nsdb.model.Record
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

class TestCommitLogService extends Actor {
  def receive = {
    case Insert(ts, metric, record) =>
      sender() ! WroteToCommitLogAck(ts, metric, record)
    case Delete(_, _) => sys.error("Not Implemented")
  }
}

class WriteCoordinatorSpec
    extends TestKit(ActorSystem("nsdb-test"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  val probe       = TestProbe()
  val probeActor  = probe.ref
  val schemaActor = system.actorOf(SchemaActor.props("target/test_index"))
  val writeCoordinatorActor = system actorOf WriteCoordinator.props(
    schemaActor,
    system.actorOf(Props[TestCommitLogService]),
    system.actorOf(IndexerActor.props("target/test_index")))

  "WriteCoordinator" should "write records" in {
    val record1 = Record(System.currentTimeMillis, Map("content" -> s"content"), Map.empty)
    val record2 = Record(System.currentTimeMillis, Map("content" -> s"content", "content2" -> s"content2"), Map.empty)
    val incompatibleRecord =
      Record(System.currentTimeMillis, Map("content" -> 1, "content2" -> s"content2"), Map.empty)

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "testMetric", record1))

    val expectedAdd = probe.expectMsgType[InputMapped]
    expectedAdd.metric shouldBe "testMetric"
    expectedAdd.record shouldBe record1

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "testMetric", record2))

    val expectedAdd2 = probe.expectMsgType[InputMapped]
    expectedAdd2.metric shouldBe "testMetric"
    expectedAdd2.record shouldBe record2

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "testMetric", incompatibleRecord))

    probe.expectMsgType[RecordRejected]

  }

}