package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.server.terminal.ShellTopics;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDelivery;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 验证 {@link ShellEventPublisher} 正确将终端响应包装为 {@link TerminalDelivery} 并发布至目标节点。 */
class ShellEventPublisherTest {

  private static final UUID BUS_NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void constructorRejectsNullBus() {
    assertThrows(NullPointerException.class, () -> new ShellEventPublisher(null));
  }

  @Test
  void onTerminalResponseRejectsNullArguments() {
    RecordingNotificationBus bus = new RecordingNotificationBus(BUS_NODE_ID);
    ShellEventPublisher publisher = new ShellEventPublisher(bus);

    UUID leaseToken = UUID.randomUUID();
    UUID daemonInstanceId = UUID.randomUUID();
    TerminalResponse response = sampleResponse(UUID.randomUUID());

    assertThrows(
        NullPointerException.class,
        () -> publisher.onTerminalResponse(null, daemonInstanceId, response));
    assertThrows(
        NullPointerException.class, () -> publisher.onTerminalResponse(leaseToken, null, response));
    assertThrows(
        NullPointerException.class,
        () -> publisher.onTerminalResponse(leaseToken, daemonInstanceId, null));
  }

  @Test
  void routesTerminalDeliveryToTargetAppNode() {
    RecordingNotificationBus bus = new RecordingNotificationBus(BUS_NODE_ID);
    ShellEventPublisher publisher = new ShellEventPublisher(bus);

    UUID appNodeId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    UUID daemonInstanceId = UUID.randomUUID();
    TerminalResponse response = sampleResponse(appNodeId);

    publisher.onTerminalResponse(leaseToken, daemonInstanceId, response);

    assertEquals(1, bus.publishCount);
    assertSame(ShellTopics.EVENT, bus.publishedTopic);
    assertEquals(NotificationAddress.node(appNodeId), bus.publishedAddress);

    TerminalDelivery delivery = assertInstanceOf(TerminalDelivery.class, bus.publishedPayload);
    assertEquals(BUS_NODE_ID, delivery.ownerNodeId());
    assertEquals(leaseToken, delivery.leaseToken());
    assertEquals(daemonInstanceId, delivery.daemonInstanceId());
    assertSame(response, delivery.response());
  }

  private static TerminalResponse sampleResponse(UUID appNodeId) {
    UUID environmentUuid = UUID.randomUUID();
    UUID viewerUuid = UUID.randomUUID();
    UUID daemonUuid = UUID.randomUUID();
    UUID terminalUuid = UUID.randomUUID();
    TerminalRoute route = new TerminalRoute(appNodeId, "conn-test-1");
    TerminalEvent event =
        new TerminalEvent(
            null,
            environmentUuid,
            viewerUuid,
            new TerminalIdentity(daemonUuid, terminalUuid),
            new TerminalEvent.ErrorPayload(ErrorCode.BUSY, ErrorDisposition.NOT_EXECUTED));
    return new TerminalResponse(route, event);
  }

  private static final class RecordingNotificationBus implements NotificationBus {

    private final UUID nodeId;
    int publishCount = 0;
    NotificationTopic<?> publishedTopic;
    NotificationAddress publishedAddress;
    Object publishedPayload;

    RecordingNotificationBus(UUID nodeId) {
      this.nodeId = nodeId;
    }

    @Override
    public UUID nodeId() {
      return nodeId;
    }

    @Override
    public <T> void publish(NotificationTopic<T> topic, NotificationAddress address, T payload) {
      this.publishCount++;
      this.publishedTopic = topic;
      this.publishedAddress = address;
      this.publishedPayload = payload;
    }

    @Override
    public <T> void publishBatch(
        NotificationTopic<T> topic, NotificationAddress address, List<T> payloads) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> NotificationSubscription subscribe(
        NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException();
    }
  }
}
