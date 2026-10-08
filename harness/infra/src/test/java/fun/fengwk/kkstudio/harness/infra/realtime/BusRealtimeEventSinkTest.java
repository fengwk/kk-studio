package fun.fengwk.kkstudio.harness.infra.realtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeNotificationCodec.Envelope;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.PostgresqlHarnessStoreFixture;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** BusRealtimeEventSink 的单条与批量发布、payload 上限降级 Resync、顺序保持与参数校验契约。 */
class BusRealtimeEventSinkTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final int DEFAULT_MAX_BYTES = 7900;
  private static final String OVERSIZE_REASON = "EVENT_TOO_LARGE";

  private DefaultNotificationBus bus;
  private BlockingQueue<Envelope> received;
  private NotificationSubscription subscription;

  @BeforeEach
  void setUp() {
    bus = PostgresqlHarnessStoreFixture.newBus();
    received = new LinkedBlockingQueue<>();
    subscription = bus.subscribe(HarnessNotifications.REALTIME, received::add, () -> {});
  }

  @AfterEach
  void tearDown() {
    if (subscription != null) {
      subscription.close();
    }
    if (bus != null) {
      bus.close();
    }
  }

  /** 非法构造参数与非法 append 参数必须被拒绝。 */
  @Test
  void rejectsInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new BusRealtimeEventSink(bus, 0));
    assertThrows(IllegalArgumentException.class, () -> new BusRealtimeEventSink(bus, -1));
    assertThrows(
        NullPointerException.class, () -> new BusRealtimeEventSink(null, DEFAULT_MAX_BYTES));

    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);
    assertThrows(NullPointerException.class, () -> sink.append(null));
    assertThrows(NullPointerException.class, () -> sink.appendAll(null));
  }

  /** 小型事件完整透传为 canonical Envelope.Event。 */
  @Test
  void appendSendsCanonicalEvent() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);
    RealtimeEvent.ModelDelta event = modelDelta("hello");

    sink.append(event);

    Envelope receivedEnvelope = received.poll(5, TimeUnit.SECONDS);
    Envelope.Event eventEnvelope = assertInstanceOf(Envelope.Event.class, receivedEnvelope);
    assertEquals(event, eventEnvelope.event());
  }

  /** TOOL_PARTIAL 与 MODEL_DELTA 均作为 Envelope.Event 完整透传。 */
  @Test
  void appendSendsCanonicalToolPartialEvent() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);
    RealtimeEvent.ToolPartial event = toolPartial(id(99L));

    sink.append(event);

    Envelope receivedEnvelope = received.poll(5, TimeUnit.SECONDS);
    Envelope.Event eventEnvelope = assertInstanceOf(Envelope.Event.class, receivedEnvelope);
    assertEquals(event, eventEnvelope.event());
    assertEquals(id(99L), ((RealtimeEvent.ToolPartial) eventEnvelope.event()).eventId());
  }

  /** 超过 maxMessageBytes 预算的超大事件改发该 Thread 的小型 RESYNC(reason="EVENT_TOO_LARGE")。 */
  @Test
  void oversizedEventDegradesToResyncNotification() throws Exception {
    RealtimeEvent.ModelDelta event = modelDelta("界".repeat(100));
    int eventBytes = HarnessNotifications.REALTIME.codec().encode(new Envelope.Event(event)).length;
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, eventBytes - 1);

    sink.append(event);

    Envelope receivedEnvelope = received.poll(5, TimeUnit.SECONDS);
    Envelope.Resync resync = assertInstanceOf(Envelope.Resync.class, receivedEnvelope);
    assertEquals(event.threadId(), resync.threadId());
    assertEquals(OVERSIZE_REASON, resync.reason());
  }

  /** 恰好等于 maxMessageBytes 的事件仍作为 EVENT 发送，不提前降级为 RESYNC。 */
  @Test
  void eventAtNotificationByteLimitIsStillSentAsEvent() throws Exception {
    RealtimeEvent.ModelDelta event = modelDelta("exact");
    int eventBytes = HarnessNotifications.REALTIME.codec().encode(new Envelope.Event(event)).length;
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, eventBytes);

    sink.append(event);

    Envelope receivedEnvelope = received.poll(5, TimeUnit.SECONDS);
    Envelope.Event eventEnvelope = assertInstanceOf(Envelope.Event.class, receivedEnvelope);
    assertEquals(event, eventEnvelope.event());
  }

  /** 空列表不发送任何通知。 */
  @Test
  void appendAllWithEmptyListSendsNothing() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);

    sink.appendAll(List.of());

    assertNull(received.poll(150, TimeUnit.MILLISECONDS));
  }

  /** appendAll 按序发送每个事件的完整 Envelope。 */
  @Test
  void appendAllPreservesInputOrder() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      events.add(modelDelta("delta-" + i));
    }

    sink.appendAll(events);

    for (RealtimeEvent expected : events) {
      Envelope receivedEnvelope = received.poll(5, TimeUnit.SECONDS);
      Envelope.Event eventEnvelope = assertInstanceOf(Envelope.Event.class, receivedEnvelope);
      assertEquals(expected, eventEnvelope.event());
    }
  }

  /** 批量内单个超限事件只将该事件降级为 RESYNC，同批其余事件仍保持完整 EVENT 并保序。 */
  @Test
  void appendAllDegradesOnlyOversizedEventWithinBatch() throws Exception {
    RealtimeEvent.ModelDelta normal1 = modelDelta("normal-1");
    RealtimeEvent.ModelDelta oversized = modelDelta("超大".repeat(200));
    RealtimeEvent.ModelDelta normal2 = modelDelta("normal-2");

    int normal1Bytes =
        HarnessNotifications.REALTIME.codec().encode(new Envelope.Event(normal1)).length;
    int normal2Bytes =
        HarnessNotifications.REALTIME.codec().encode(new Envelope.Event(normal2)).length;
    int limit = Math.max(normal1Bytes, normal2Bytes);

    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, limit);

    sink.appendAll(List.of(normal1, oversized, normal2));

    Envelope first = received.poll(5, TimeUnit.SECONDS);
    Envelope.Event firstEvent = assertInstanceOf(Envelope.Event.class, first);
    assertEquals(normal1, firstEvent.event());

    Envelope second = received.poll(5, TimeUnit.SECONDS);
    Envelope.Resync secondResync = assertInstanceOf(Envelope.Resync.class, second);
    assertEquals(oversized.threadId(), secondResync.threadId());
    assertEquals(OVERSIZE_REASON, secondResync.reason());

    Envelope third = received.poll(5, TimeUnit.SECONDS);
    Envelope.Event thirdEvent = assertInstanceOf(Envelope.Event.class, third);
    assertEquals(normal2, thirdEvent.event());
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private static RealtimeEvent.ToolPartial toolPartial(UUID eventId) {
    return new RealtimeEvent.ToolPartial(
        id(1L),
        id(42L),
        1,
        eventId,
        new ToolResult("call-1", List.of(new TextResultContent("partial")), false, "{}"),
        NOW);
  }
}
