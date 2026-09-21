package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * EnvironmentStateCodec 的往返与 fail-closed 测试。
 *
 * <p>两列只由本进程写入，因此解码必须严格：未知字段、错误类型、非法取值与损坏 JSON 一律抛错，绝不静默降级为「没有事实」。
 */
class EnvironmentStateCodecTest {

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
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
    assertEquals(EnvironmentSkillState.STATUS_FAILED, fresh.status());
    assertNull(fresh.installedCommit());
    assertNull(fresh.localPath());
  }

  /** 测试意图：空数组是两个投影的合法初值。 */
  @Test
  void emptyArraysRoundTrip() {
    assertEquals(List.of(), codec.decodeSkillState(codec.encodeSkillState(List.of())));
    assertEquals(List.of(), codec.decodeEvents(codec.encodeEvents(List.of())));
  }

  /** 测试意图：事件往返保留时间、级别、类型与去敏消息。 */
  @Test
  void eventsRoundTrip() {
    List<EnvironmentEvent> events =
        List.of(
            new EnvironmentEvent(
                NOW, EnvironmentEvent.LEVEL_INFO, EnvironmentEvent.TYPE_CONNECTING, "accepted"),
            new EnvironmentEvent(
                NOW.plusSeconds(1),
                EnvironmentEvent.LEVEL_WARN,
                EnvironmentEvent.TYPE_DISCONNECTED,
                "daemon connection lost"));

    assertEquals(events, codec.decodeEvents(codec.encodeEvents(events)));
    assertTrue(codec.decodeEvents(codec.encodeEvents(events)).get(1).isAlert());
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
        IllegalArgumentException.class,
        () -> new EnvironmentSkillState("pkg", "installed", null, "/home/dev/pkg", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentSkillState("pkg", "installed", "not-a-commit", "/home/dev/pkg", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentSkillState("pkg", "failed", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentSkillState("pkg", "installed", COMMIT, "/home/dev/pkg", "boom"));
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
