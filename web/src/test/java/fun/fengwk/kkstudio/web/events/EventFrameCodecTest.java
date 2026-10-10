package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 事件通道帧的严格 JSON 契约：v2 客户端 resource/shell 变体、canonical UUID；服务端帧确定性编码。 */
class EventFrameCodecTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID CANVAS = new UUID(0L, 2L);
  private static final UUID ENVIRONMENT = new UUID(0L, 5L);
  private static final UUID VIEWER = new UUID(0L, 6L);
  private static final UUID REQUEST = new UUID(0L, 7L);
  private static final UUID DAEMON = new UUID(0L, 8L);
  private static final UUID TERMINAL = new UUID(0L, 9L);
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);
  private static final ResourceKey CANVAS_KEY = new ResourceKey(ResourceKind.CANVAS, CANVAS);
  private static final ResourceKey PROJECTS_KEY = new ResourceKey(ResourceKind.PROJECTS, null);
  private static final ResourceKey TREE_KEY = new ResourceKey(ResourceKind.TREE, THREAD);
  private static final ResourceKey INTERACTIONS_KEY =
      new ResourceKey(ResourceKind.INTERACTIONS, null);
  private static final ResourceKey ENVIRONMENTS_KEY =
      new ResourceKey(ResourceKind.ENVIRONMENTS, null);
  private static final EventFrameCodec CODEC = new EventFrameCodec(new RealtimeEventJsonCodec());
  private static final TerminalControlCodec TERMINAL_CODEC = new TerminalControlCodec();

  private static TerminalCommand command() {
    return new TerminalCommand(
        REQUEST,
        ENVIRONMENT,
        VIEWER,
        new TerminalCommand.Detach(new TerminalIdentity(DAEMON, TERMINAL), new UUID(0L, 10L)));
  }

  private static TerminalEvent event() {
    return new TerminalEvent(
        REQUEST,
        ENVIRONMENT,
        VIEWER,
        new TerminalIdentity(DAEMON, TERMINAL),
        new TerminalEvent.ErrorPayload(ErrorCode.BUSY, ErrorDisposition.NOT_EXECUTED));
  }

  @Test
  void decodesResourceSubscribeAndUnsubscribeFrames() {
    assertEquals(
        new EventFrameCodec.ResourceFrame(EventFrameCodec.ResourceFrame.Type.SUBSCRIBE, THREAD_KEY),
        CODEC.decode(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + THREAD
                + "\"}}"));
    assertEquals(
        new EventFrameCodec.ResourceFrame(
            EventFrameCodec.ResourceFrame.Type.UNSUBSCRIBE, CANVAS_KEY),
        CODEC.decode(
            "{\"version\":2,\"type\":\"unsubscribe\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                + CANVAS
                + "\"}}"));
    assertEquals(
        new EventFrameCodec.ResourceFrame(
            EventFrameCodec.ResourceFrame.Type.SUBSCRIBE, PROJECTS_KEY),
        CODEC.decode(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"projects\"}}"));
    // 执行树按真实根 id 订阅；交互与 Environment 是无 id 的全局资源。
    assertEquals(
        new EventFrameCodec.ResourceFrame(EventFrameCodec.ResourceFrame.Type.SUBSCRIBE, TREE_KEY),
        CODEC.decode(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"tree\",\"id\":\""
                + THREAD
                + "\"}}"));
    assertEquals(
        new EventFrameCodec.ResourceFrame(
            EventFrameCodec.ResourceFrame.Type.SUBSCRIBE, INTERACTIONS_KEY),
        CODEC.decode(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"interactions\"}}"));
    assertEquals(
        new EventFrameCodec.ResourceFrame(
            EventFrameCodec.ResourceFrame.Type.SUBSCRIBE, ENVIRONMENTS_KEY),
        CODEC.decode(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"environments\"}}"));
  }

  @Test
  void decodesShellCommandFrameReusingTheControlCodec() {
    EventFrameCodec.ClientFrame frame =
        CODEC.decode(
            "{\"version\":2,\"type\":\"shell.command\",\"command\":"
                + TERMINAL_CODEC.encodeCommand(command())
                + "}");
    assertEquals(new EventFrameCodec.ShellCommand(command()), frame);
  }

  @Test
  void encodesShellEventFrameReusingTheControlCodec() {
    assertEquals(
        "{\"version\":2,\"type\":\"shell.event\",\"event\":"
            + TERMINAL_CODEC.encodeEvent(event())
            + "}",
        CODEC.shellEvent(event()));
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
  void rejectsMissingOrWrongVersionIncludingLegacyV1() {
    String base =
        "\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode("{" + base + "}"));
    // 旧逻辑版本 v1 必须被拒绝：不保留兼容 decoder。
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode("{\"version\":1," + base + "}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode("{\"version\":\"2\"," + base + "}"));
    // version 必须是精确 integer 2：溢出整数（低 64 位截断为 2）与浮点 2.0 均拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":18446744073709551618," + base + "}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode("{\"version\":2.0," + base + "}"));
  }

  @Test
  void rejectsNonCanonicalTypeCase() {
    String resource = "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":2,\"type\":\"SUBSCRIBE\",\"resource\":" + resource + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":2,\"type\":\"Subscribe\",\"resource\":" + resource + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"UNSUBSCRIBE\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                    + CANVAS
                    + "\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"SHELL.COMMAND\",\"command\":"
                    + TERMINAL_CODEC.encodeCommand(command())
                    + "}"));
  }

  @Test
  void rejectsShellFrameWithResourceOrUnknownFields() {
    String shell =
        "{\"version\":2,\"type\":\"shell.command\",\"command\":"
            + TERMINAL_CODEC.encodeCommand(command())
            + "}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                shell.replace(
                    ",\"command\"", ",\"resource\":{\"kind\":\"projects\"},\"command\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(shell.replace(",\"command\"", ",\"extra\":\"x\",\"command\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":2,\"type\":\"shell.command\"}"));
    // route/owner/lease 绝不出现在浏览器 shell 帧内；嵌套命令字段严格。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"shell.command\",\"command\":{\"type\":\"DETACH\"}}"));
  }

  @Test
  void rejectsResourceFrameWithAfterCursorAndUnknownFields() {
    String resource = "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":"
                    + resource
                    + ",\"after\":\"0\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":"
                    + resource
                    + ",\"extra\":\"x\"}"));
  }

  @Test
  void rejectsMalformedResources() {
    String prefix = "{\"version\":2,\"type\":\"subscribe\",\"resource\":";
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
        IllegalArgumentException.class,
        () -> CODEC.decode(prefix + "{\"kind\":\"interactions\",\"id\":\"" + THREAD + "\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(prefix + "{\"kind\":\"environments\",\"id\":\"" + THREAD + "\"}}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode(prefix + "{\"kind\":\"tree\"}}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode(prefix + "{\"kind\":\"thread\"}}"));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(prefix + "\"nope\"}"));
  }

  @Test
  void rejectsUnknownTypeAndMalformedJson() {
    String resource = "{\"kind\":\"thread\",\"id\":\"" + THREAD + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode("{\"version\":2,\"type\":\"ping\",\"resource\":" + resource + "}"));
    IllegalArgumentException invalidJson =
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode("{not json"));
    assertEquals("malformed client frame JSON", invalidJson.getMessage());
    assertNull(invalidJson.getCause());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":" + resource + "} trailing"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + THREAD
                    + "\",\"id\":\""
                    + THREAD
                    + "\"}}"));
  }

  @Test
  void encodesSubscribedFrameWithVersionAndCanonicalCursor() {
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"7\"}",
        CODEC.subscribed(THREAD_KEY, 7L));
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"projects\"},\"cursor\":\"0\"}",
        CODEC.subscribed(PROJECTS_KEY, 0L));
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"tree\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"0\"}",
        CODEC.subscribed(TREE_KEY, 0L));
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"interactions\"},\"cursor\":\"0\"}",
        CODEC.subscribed(INTERACTIONS_KEY, 0L));
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"environments\"},\"cursor\":\"0\"}",
        CODEC.subscribed(ENVIRONMENTS_KEY, 0L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(THREAD_KEY, -1L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(PROJECTS_KEY, 1L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(TREE_KEY, 1L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(INTERACTIONS_KEY, 1L));
    assertThrows(IllegalArgumentException.class, () -> CODEC.subscribed(ENVIRONMENTS_KEY, 1L));
  }

  @Test
  void encodesEventFramesWithUnifiedNameAndDataShape() {
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"version\",\"cursor\":\"8\",\"data\":{\"version\":\"8\"}}",
        CODEC.event(THREAD_KEY, new Signal.Version("8")));
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
            + CANVAS
            + "\"},\"name\":\"revision\",\"cursor\":\"3\",\"data\":{\"revision\":\"3\"}}",
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
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"realtime\",\"data\":"
            + new RealtimeEventJsonCodec().encode(delta)
            + "}",
        CODEC.event(THREAD_KEY, new Signal.Realtime(delta)));

    UUID projectId = new UUID(0L, 8L);
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"projects\"},\"name\":\"changed\",\"data\":{\"projectId\":\""
            + projectId
            + "\"}}",
        CODEC.event(PROJECTS_KEY, new Signal.ProjectChanged(projectId)));

    // 提示型资源：执行树/Environment 的 data 为空对象（标识只在 resource），交互携带真实执行根。
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"tree\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"changed\",\"data\":{}}",
        CODEC.event(TREE_KEY, new Signal.TreeChanged()));
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"environments\"},\"name\":\"changed\",\"data\":{}}",
        CODEC.event(ENVIRONMENTS_KEY, new Signal.EnvironmentChanged()));
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"interactions\"},\"name\":\"changed\",\"data\":{\"rootThreadId\":\""
            + THREAD
            + "\"}}",
        CODEC.event(INTERACTIONS_KEY, new Signal.InteractionsChanged(THREAD)));
  }

  @Test
  void hintSignalsRequireTheirOwnResourceKind() {
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.event(PROJECTS_KEY, new Signal.TreeChanged()));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.event(TREE_KEY, new Signal.EnvironmentChanged()));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.event(TREE_KEY, new Signal.InteractionsChanged(THREAD)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.event(INTERACTIONS_KEY, new Signal.TreeChanged()));
  }

  @Test
  void encodesResyncHeartbeatAndErrorFrames() {
    assertEquals(
        "{\"version\":2,\"type\":\"resync\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
            + CANVAS
            + "\"}}",
        CODEC.resync(CANVAS_KEY));
    assertEquals("{\"version\":2,\"type\":\"heartbeat\"}", CODEC.heartbeat());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"boom\"}",
        CODEC.error(EventFrameCodec.INVALID_FRAME, "boom"));
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"missing\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        CODEC.error(EventFrameCodec.RESOURCE_NOT_FOUND, "missing", THREAD_KEY));
  }

  @Test
  void resyncSignalIsNotAnEventFrame() {
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.event(THREAD_KEY, new Signal.Resync()));
  }

  /**
   * realtime 帧对 ToolPartial 只做原样透传：data 就是 runtime codec 的 canonical Tool partial JSON，eventId 不丢失。
   */
  @Test
  void passesThroughCanonicalToolPartialData() {
    RealtimeEvent.ToolPartial partial =
        new RealtimeEvent.ToolPartial(
            THREAD,
            new UUID(0L, 7L),
            1,
            new UUID(0L, 9L),
            new ToolResult("call-1", List.of(new TextResultContent("partial")), false, "{}"),
            Instant.parse("2026-08-05T00:00:00Z"));
    String data = new RealtimeEventJsonCodec().encode(partial);

    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"realtime\",\"data\":"
            + data
            + "}",
        CODEC.event(THREAD_KEY, new Signal.Realtime(partial)));
  }

  @Test
  void shellCommandFrameIsADistinctVariant() {
    assertInstanceOf(
        EventFrameCodec.ShellCommand.class,
        CODEC.decode(
            "{\"version\":2,\"type\":\"shell.command\",\"command\":"
                + TERMINAL_CODEC.encodeCommand(command())
                + "}"));
  }
}
