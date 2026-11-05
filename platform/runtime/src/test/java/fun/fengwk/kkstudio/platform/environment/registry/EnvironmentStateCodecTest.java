package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * EnvironmentStateCodec 的往返与 fail-closed 测试。
 *
 * <p>两列只由本进程写入，因此解码必须严格：未知字段、错误类型、非法取值与损坏 JSON 一律抛错，绝不静默降级为「没有事实」。
 */
class EnvironmentStateCodecTest {

  private static final String COMMIT = "0123456789abcdef".repeat(4);
  private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");

  private final EnvironmentStateCodec codec = new EnvironmentStateCodec();

  /** 测试意图：安装成功与安装失败（保留上一次可用安装）两种投影都能无损往返。 */
  @Test
  void skillStateRoundTrips() {
    List<EnvironmentSkillState> states =
        List.of(
            EnvironmentSkillState.installed("pkg", COMMIT, "/home/dev/.kkstudio/skills/pkg"),
            EnvironmentSkillState.failed(
                "other",
                EnvironmentSkillState.installed("other", COMMIT, "/home/dev/old"),
                "skill package sync failed: COMMIT_NOT_FOUND"),
            EnvironmentSkillState.failed("fresh", null, "skill package sync could not be sent"));

    List<EnvironmentSkillState> decoded = codec.decodeSkillState(codec.encodeSkillState(states));

    assertEquals(states, decoded);
    EnvironmentSkillState fresh = decoded.get(2);
    assertNull(fresh.installedSha256());
    assertNull(fresh.localPath());
    assertEquals("skill package sync could not be sent", fresh.error());
  }

  /** 测试意图：空数组是两个投影的合法初值。 */
  @Test
  void emptyArraysRoundTrip() {
    assertEquals(List.of(), codec.decodeSkillState(codec.encodeSkillState(List.of())));
    assertEquals(List.of(), codec.decodeEvents("[]"));
  }

  /** 测试意图：单条事件编码结果可在事件数组中正确解码，并保留时间、级别、类型与去敏消息。 */
  @Test
  void eventsRoundTrip() {
    EnvironmentEvent event1 =
        new EnvironmentEvent(
            NOW, EnvironmentEvent.LEVEL_INFO, EnvironmentEvent.TYPE_CONNECTING, "accepted");
    EnvironmentEvent event2 =
        new EnvironmentEvent(
            NOW.plusSeconds(1),
            EnvironmentEvent.LEVEL_WARN,
            EnvironmentEvent.TYPE_DISCONNECTED,
            "daemon connection lost");
    String json = "[" + codec.encodeEvent(event1) + "," + codec.encodeEvent(event2) + "]";

    assertEquals(List.of(event1, event2), codec.decodeEvents(json));
  }

  /** 测试意图：未知字段、错误类型、非法取值与非数组内容一律 fail-closed。 */
  @Test
  void malformedProjectionsAreRejected() {
    String[] malformedSkillState = {
      "{\"packageName\":\"pkg\"}",
      "[{\"packageName\":\"pkg\",\"status\":\"installed\"}]",
      "[{\"packageName\":\"pkg\",\"status\":\"installed\",\"installedCommit\":\""
          + COMMIT
          + "\",\"localPath\":\"/home/dev/pkg\",\"unknown\":1}]",
      "[{\"packageName\":\"pkg\",\"status\":\"installed\",\"installedCommit\":123}]",
      "[{\"packageName\":\"pkg\",\"status\":\"pending\",\"localPath\":\"/home/dev/pkg\"}]",
      "[{\"packageName\":\"pkg\",\"status\":\"failed\",\"error\":\"e\"},\"trailing\"]",
      "not-json"
    };
    for (String json : malformedSkillState) {
      // 规范层严格性与投影自身不变量都必须 fail-closed，绝不允许降级成空投影。
      assertThrows(
          RuntimeException.class,
          () -> codec.decodeSkillState(json),
          "expected rejection of " + json);
    }

    String[] malformedEvents = {
      "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"INFO\",\"type\":\"READY\"}]",
      "[{\"time\":\"yesterday\",\"level\":\"INFO\",\"type\":\"READY\",\"message\":\"m\"}]",
      "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"TRACE\",\"type\":\"READY\",\"message\":\"m\"}]",
      "[{\"time\":\"2026-09-21T06:00:00Z\",\"level\":\"INFO\",\"type\":\"READY\",\"message\":\"m\",\"x\":1}]",
      "{}"
    };
    for (String json : malformedEvents) {
      assertThrows(
          RuntimeException.class, () -> codec.decodeEvents(json), "expected rejection of " + json);
    }
  }

  /** 测试意图：投影类型自身的不变量（状态与字段组合、错误码语法、去敏文本边界）在构造期即被拒绝。 */
  @Test
  void projectionInvariantsAreEnforced() {
    assertThrows(
        IllegalArgumentException.class, () -> new EnvironmentSkillState("pkg", COMMIT, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentSkillState("pkg", "not-a-sha256", "/home/dev/pkg", null));
    assertNull(new EnvironmentSkillState("pkg", null, null, null).installedSha256());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentSkillState("pkg", COMMIT, "/home/dev/pkg", " ".repeat(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentEvent(NOW, "TRACE", EnvironmentEvent.TYPE_READY, "m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentEvent(NOW, EnvironmentEvent.LEVEL_INFO, "SYNCING", "m"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentEvent(
                NOW, EnvironmentEvent.LEVEL_INFO, EnvironmentEvent.TYPE_READY, " "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentEvent(
                NOW,
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_READY,
                "x".repeat(EnvironmentEvent.MAX_MESSAGE_CHARS + 1)));
  }
}
