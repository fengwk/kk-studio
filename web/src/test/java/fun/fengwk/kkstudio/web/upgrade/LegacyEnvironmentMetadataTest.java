package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;

import java.util.List;
import java.util.function.Consumer;

/** 源格式必须精确属于冻结 profile；离线转换不能借当前 codec 的较宽类型掩盖旧数据损坏。 */
class LegacyEnvironmentMetadataTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String DEV =
      LegacyReferenceDataFixture.resource("legacy-dev-ready-metadata.json");

  private static final String MAIN =
      LegacyReferenceDataFixture.resource("legacy-main-ready-metadata.json");

  @Test
  void devAddsOnlyTheExplicitlyUnknownArchitecture() {
    ObjectNode expected = (ObjectNode) MAPPER.readTree(DEV);
    expected.put("version", 5);
    ((ObjectNode) expected.get("environment")).putNull("architecture");

    String converted = LegacyEnvironmentMetadata.runtimeInfo(DEV, "dev");

    assertEquals(expected, MAPPER.readTree(converted));
    assertNull(new DaemonCapabilitiesCodec().decode(converted).environment().architecture());
    assertEquals(4, MAPPER.readTree(DEV).get("version").intValue());
  }

  @Test
  void mainRetainsBothNativeArchitecturesAndNullMetadataIsNotInvented() {
    for (String architecture : List.of("amd64", "arm64")) {
      String source = MAIN.replace("amd64", architecture);
      assertSame(source, LegacyEnvironmentMetadata.runtimeInfo(source, "main"));
      assertEquals(
          architecture, new DaemonCapabilitiesCodec().decode(source).environment().architecture());
    }
    assertNull(LegacyEnvironmentMetadata.runtimeInfo(null, "dev"));
    assertNull(LegacyEnvironmentMetadata.runtimeInfo(null, "main"));
  }

  @Test
  void rejectsMalformedAndCrossProfileMetadataWithoutInputOrCause() {
    for (String invalid :
        List.of(
            "null",
            "[]",
            "{}",
            DEV + " false",
            DEV.replace("\"version\": 4", "\"version\": 4, \"version\": 4"),
            DEV.replace("\"version\": 4", "\"version\": \"4\""),
            DEV.replace("\"version\": 4", "\"version\": 4.0"),
            DEV.replace("\"version\": 4", "\"version\": 4294967300"),
            DEV.replace("\"version\": 4", "\"version\": 5"),
            DEV.replace("\"environment\": {", "\"environment\": null, \"extra\": {"),
            mutate(DEV, root -> root.remove("version")),
            mutate(DEV, root -> root.put("untrusted-field-marker", "untrusted-value-marker")),
            mutate(DEV, root -> environment(root).put("architecture", "amd64")),
            mutate(DEV, root -> environment(root).putNull("architecture")),
            mutate(DEV, root -> environment(root).remove("note")),
            mutate(DEV, root -> environment(root).put("timeZone", "untrusted-value-marker")))) {
      assertRejected(invalid, "dev");
    }
    for (String invalid :
        List.of(
            DEV,
            mutate(MAIN, root -> environment(root).remove("architecture")),
            mutate(MAIN, root -> environment(root).putNull("architecture")),
            mutate(MAIN, root -> environment(root).put("architecture", "x86_64")),
            mutate(MAIN, root -> environment(root).put("architecture", 42)),
            mutate(MAIN, root -> root.put("untrusted-field-marker", "untrusted-value-marker")))) {
      assertRejected(invalid, "main");
    }
    assertRejected(MAIN, "unknown");
    assertRejected(MAIN, "dev");
  }

  private static String mutate(String json, Consumer<ObjectNode> mutation) {
    ObjectNode root = (ObjectNode) MAPPER.readTree(json);
    mutation.accept(root);
    return MAPPER.writeValueAsString(root);
  }

  private static ObjectNode environment(ObjectNode root) {
    return (ObjectNode) root.get("environment");
  }

  private static void assertRejected(String json, String profile) {
    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> LegacyEnvironmentMetadata.runtimeInfo(json, profile));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, error.stage());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    assertFalse(error.getMessage().contains("untrusted"));
  }
}
