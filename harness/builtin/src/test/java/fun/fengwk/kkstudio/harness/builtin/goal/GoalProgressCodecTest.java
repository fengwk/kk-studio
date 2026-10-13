package fun.fengwk.kkstudio.harness.builtin.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** {@link GoalProgressCodec} 的 canonical 往返与 fail-closed 拒绝边界。 */
class GoalProgressCodecTest {

  private static final JsonMapper FIXTURE_MAPPER = JsonMapper.builder().build();
  private static final UUID GOAL_ID = new UUID(0L, 42L);
  private static final Instant REPORTED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final GoalProgressCodec codec = new GoalProgressCodec();

  /** 终态声明在 canonical JSON 上无损往返，字段顺序与 wire 值稳定。 */
  @Test
  void roundTripsCanonicalProgress() {
    GoalProgress progress =
        new GoalProgress(GOAL_ID, GoalStatus.BLOCKED, "needs input", REPORTED_AT);

    String encoded = codec.encode(progress);
    GoalProgress decoded =
        codec.decode(new CustomStateSnapshot(GoalProgressCodec.SCHEMA_VERSION, encoded));

    assertEquals(
        "{\"goalId\":\""
            + GOAL_ID
            + "\",\"status\":\"blocked\",\"reason\":\"needs input\","
            + "\"reportedAt\":\"2026-01-01T00:00:00Z\"}",
        encoded);
    assertEquals(progress, decoded);
  }

  /**
   * 非 {@code schemaVersion = 1}、非 object、字段集不符、UUID/状态/文本/时间戳非法，以及空白或 malformed 原始文本一律 fail-closed
   * 拒绝；每个用例都来自真实 fixture，而不是为覆盖率构造的路径。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidProgress")
  void rejectsInvalidProgressFixtures(
      String name, String mode, int schemaVersion, String document, String errorFragment) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              if ("parse".equals(mode)) {
                codec.parse(document, "goal progress");
              } else {
                codec.decode(new CustomStateSnapshot(schemaVersion, document));
              }
            });
    assertTrue(
        error.getMessage().contains(errorFragment),
        () -> name + ": expected <" + errorFragment + "> but was <" + error.getMessage() + ">");
  }

  private static Stream<Arguments> invalidProgress() {
    JsonNode fixtures = readFixture("invalid-progress.json");
    List<Arguments> cases = new ArrayList<>();
    for (JsonNode fixture : fixtures) {
      String mode = fixture.get("mode").asString();
      String document =
          "parse".equals(mode)
              ? fixture.get("raw").asString()
              : FIXTURE_MAPPER.writeValueAsString(fixture.get("data"));
      cases.add(
          Arguments.of(
              fixture.get("name").asString(),
              mode,
              fixture.get("schemaVersion").asInt(),
              document,
              fixture.get("error").asString()));
    }
    return cases.stream();
  }

  private static JsonNode readFixture(String name) {
    try (InputStream in = GoalProgressCodecTest.class.getResourceAsStream(name)) {
      assertNotNull(in, name + " must exist in test resources");
      return FIXTURE_MAPPER.readTree(in);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
