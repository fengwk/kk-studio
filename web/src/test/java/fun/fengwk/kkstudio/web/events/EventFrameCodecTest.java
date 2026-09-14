package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;

import java.time.Instant;
import java.util.UUID;

/** 事件通道帧的严格 JSON 契约：客户端帧精确字段集、canonical UUID；服务端帧确定性编码。 */
class EventFrameCodecTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID CANVAS = new UUID(0L, 2L);
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);
  private static final ResourceKey CANVAS_KEY = new ResourceKey(ResourceKind.CANVAS, CANVAS);
  private static final ResourceKey PROJECTS_KEY = new ResourceKey(ResourceKind.PROJECTS, null);
  private static final EventFrameCodec CODEC = new EventFrameCodec(new RealtimeEventJsonCodec());

  @Test
  void decodesSubscribeAndUnsubscribeFrames() {
    assertEquals(
        new EventFrameCodec.ClientFrame(EventFrameCodec.ClientFrame.Type.SUBSCRIBE, THREAD_KEY),
        CODEC.decode(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + THREAD
                + "\"}}"));
    assertEquals(
        new EventFrameCodec.ClientFrame(EventFrameCodec.ClientFrame.Type.UNSUBSCRIBE, CANVAS_KEY),
        CODEC.decode(
            "{\"version\":1,\"type\":\"unsubscribe\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                + CANVAS
                + "\"}}"));
    assertEquals(
        new EventFrameCodec.ClientFrame(EventFrameCodec.ClientFrame.Type.SUBSCRIBE, PROJECTS_KEY),
        CODEC.decode(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"projects\"}}"));
  }

  @Test
  void rejectsFramesWithoutTypeAndVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"action\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + THREAD
                    + "\"}}"));
  }

  @Test
  void rejectsMissingOrWrongVersion() {
    String base =
        "\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode("{" + base + "}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode("{\"version\":2," + base + "}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode("{\"version\":\"1\"," + base + "}"));
    // version 必须是精确 integer 1：溢出整数（低 64 位截断为 1）与浮点 1.0 均拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":18446744073709551617," + base + "}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode("{\"version\":1.0," + base + "}"));
  }

  @Test
  void rejectsNonCanonicalTypeCase() {
    String resource = "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":1,\"type\":\"SUBSCRIBE\",\"resource\":" + resource + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":1,\"type\":\"Subscribe\",\"resource\":" + resource + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":1,\"type\":\"UNSUBSCRIBE\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                    + CANVAS
                    + "\"}}"));
  }

  @Test
  void rejectsAfterCursorAndUnknownFields() {
    String resource = "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":1,\"type\":\"subscribe\",\"resource\":"
                    + resource
                    + ",\"after\":\"0\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":1,\"type\":\"subscribe\",\"resource\":"
                    + resource
                    + ",\"extra\":\"x\"}"));
  }

  @Test
  void rejectsMalformedResources() {
    String prefix = "{\"version\":1,\"type\":\"subscribe\",\"resource\":";
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(prefix + "{\"kind\":\"agent\",\"id\":\"" + THREAD + "\"}}"));
    IllegalArgumentException invalidId =
        assertThrows(
            IllegalArgumentException.class,
            () -> CODEC.decode(prefix + "{\"kind\":\"thread\",\"id\":\"not-a-uuid\"}}"));
    assertEquals("frame.resource.id must be a canonical UUID", invalidId.getMessage());
    assertNull(invalidId.getCause());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                prefix + "{\"kind\":\"thread\",\"id\":\"00000000-0000-0000-0000-ABCDEF012345\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(prefix + "{\"kind\":\"thread\",\"id\":\"{" + THREAD + "}\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                prefix + "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\",\"extra\":true}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(prefix + "{\"kind\":\"projects\",\"id\":\"" + THREAD + "\"}}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode(prefix + "{\"kind\":\"thread\"}}"));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(prefix + "\"nope\"}"));
  }

  @Test
  void rejectsUnknownTypeAndMalformedJson() {
    String resource = "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":1,\"type\":\"ping\",\"resource\":" + resource + "}"));
    IllegalArgumentException invalidJson =
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode("{not json"));
    assertEquals("malformed client frame JSON", invalidJson.getMessage());
    assertNull(invalidJson.getCause());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":1,\"type\":\"subscribe\",\"resource\":" + resource + "} trailing"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + THREAD
                    + "\",\"id\":\""
                    + THREAD
                    + "\"}}"));
  }

  @Test
  void encodesSubscribedFrameWithVersionAndCanonicalCursor() {
    assertEquals(
        "{\"version\":1,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"7\"}",
        CODEC.subscribed(THREAD_KEY, 7L));
    assertEquals(
        "{\"version\":1,\"type\":\"subscribed\",\"resource\":{\"kind\":\"projects\"},\"cursor\":\"0\"}",
        CODEC.subscribed(PROJECTS_KEY, 0L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(THREAD_KEY, -1L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(PROJECTS_KEY, 1L));
  }

  @Test
  void encodesEventFramesWithUnifiedNameAndDataShape() {
    assertEquals(
        "{\"version\":1,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"version\",\"cursor\":\"8\",\"data\":{\"version\":\"8\"}}",
        CODEC.event(THREAD_KEY, new Signal.Version("8")));
    assertEquals(
        "{\"version\":1,\"type\":\"event\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
            + CANVAS
            + "\"},\"name\":\"version\",\"cursor\":\"3\",\"data\":{\"version\":\"3\"}}",
        CODEC.event(CANVAS_KEY, new Signal.Version("3")));

    RealtimeEvent.ModelDelta delta =
        new RealtimeEvent.ModelDelta(
            THREAD,
            new UUID(0L, 9L),
            1,
            1L,
            new ProviderStreamEvent.TextDelta("hi"),
            Instant.parse("2026-08-05T00:00:00Z"));
    assertEquals(
        "{\"version\":1,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"realtime\",\"data\":"
            + new RealtimeEventJsonCodec().encode(delta)
            + "}",
        CODEC.event(THREAD_KEY, new Signal.Realtime(delta)));

    UUID projectId = new UUID(0L, 8L);
    assertEquals(
        "{\"version\":1,\"type\":\"event\",\"resource\":{\"kind\":\"projects\"},\"name\":\"changed\",\"data\":{\"projectId\":\""
            + projectId
            + "\"}}",
        CODEC.event(PROJECTS_KEY, new Signal.ProjectChanged(projectId)));
  }

  @Test
  void encodesResyncHeartbeatAndErrorFrames() {
    assertEquals(
        "{\"version\":1,\"type\":\"resync\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
            + CANVAS
            + "\"}}",
        CODEC.resync(CANVAS_KEY));
    assertEquals("{\"version\":1,\"type\":\"heartbeat\"}", CODEC.heartbeat());
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"boom\"}",
        CODEC.error(EventFrameCodec.INVALID_FRAME, "boom"));
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"missing\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        CODEC.error(EventFrameCodec.RESOURCE_NOT_FOUND, "missing", THREAD_KEY));
  }

  @Test
  void resyncSignalIsNotAnEventFrame() {
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.event(THREAD_KEY, new Signal.Resync()));
  }
}
