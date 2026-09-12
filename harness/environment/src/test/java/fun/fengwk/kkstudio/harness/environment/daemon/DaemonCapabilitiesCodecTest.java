package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** READY capabilities v2 codec 的严格版本、来源集合版本、来源快照、唯一性与 environment metadata 契约。 */
class DaemonCapabilitiesCodecTest {

  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(
          DaemonOperatingSystem.LINUX, "Asia/Shanghai", "Linux environment.", "/home/dev");
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"note\":\"Linux environment.\","
          + "\"rootPath\":\"/home/dev\"}";

  private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  /** 来源集合版本与行级 sourceVersion 是两个独立维度，测试用固定非零值区分二者。 */
  private static final long SOURCE_SET_VERSION = 7L;

  private static final String REVISION = "a".repeat(64);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  /** 完整来源快照必须精确往返，wire 只包含新形状（没有旧顶层 skills），且集合版本位于 environment 与 skillSources 之间。 */
  @Test
  void roundTripsCapabilitiesWithSourceSnapshots() {
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(
            SOURCE_ID,
            3,
            REVISION,
            List.of(
                new DaemonSkillDescriptor(
                    SOURCE_ID,
                    3,
                    "dev",
                    "Developer rules",
                    "/home/dev/.agents/skills/dev",
                    REVISION)),
            List.of(
                new DaemonSkillDiagnostic(
                    "/home/dev/.agents/skills/broken", "missing front matter name")));
    DaemonCapabilities original =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION, ENVIRONMENT, SOURCE_SET_VERSION, List.of(snapshot));

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals(
        "{\"version\":2,"
            + ENVIRONMENT_JSON
            + ",\"sourceSetVersion\":7,\"skillSources\":[{\"sourceId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sourceVersion\":3,\"sourceRevision\":\""
            + REVISION
            + "\",\"skills\":[{\"sourceId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sourceVersion\":3,\"name\":\"dev\",\"description\":\"Developer rules\","
            + "\"baseDirectory\":\"/home/dev/.agents/skills/dev\",\"contentRevision\":\""
            + REVISION
            + "\"}],\"diagnostics\":[{\"location\":\"/home/dev/.agents/skills/broken\","
            + "\"message\":\"missing front matter name\"}]}]}",
        encoded);
  }

  @Test
  void encodesEmptySkillSources() {
    assertEquals(
        "{\"version\":2," + ENVIRONMENT_JSON + ",\"sourceSetVersion\":0,\"skillSources\":[]}",
        codec.encode(
            new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, 0, List.of())));
  }

  /** sourceSetVersion 是最终 v2 契约的必填字段：缺失、负数与错误类型都在 wire 边界拒绝，不做缺省推断。 */
  @Test
  void rejectsMissingOrInvalidSourceSetVersion() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2," + ENVIRONMENT_JSON + ",\"skillSources\":[]}"));
    for (String invalid : new String[] {"-1", "1.5", "\"7\"", "null", "true", "{}", "[]"}) {
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":2,"
                      + ENVIRONMENT_JSON
                      + ",\"sourceSetVersion\":"
                      + invalid
                      + ",\"skillSources\":[]}"),
          invalid);
    }
    // 重复集合版本是自相矛盾的围栏事实，按重复键拒绝。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"sourceSetVersion\":1,\"skillSources\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, -1, List.of()));
  }

  /** 边界集合版本必须原样往返：0 表示尚未接受任何集合事实，Long 上界不被截断。 */
  @Test
  void roundTripsSourceSetVersionBoundaries() {
    assertEquals(
        0L,
        codec
            .decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}")
            .sourceSetVersion());
    assertEquals(
        Long.MAX_VALUE,
        codec
            .decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":9223372036854775807,\"skillSources\":[]}")
            .sourceSetVersion());
    assertEquals(
        "{\"version\":2," + ENVIRONMENT_JSON + ",\"sourceSetVersion\":7,\"skillSources\":[]}",
        codec.encode(
            new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, 7, List.of())));
  }

  /** 旧顶层 skills 形状与所有非 v2 版本都必须被拒绝，不做双解码。 */
  @Test
  void rejectsLegacyAndUnsupportedVersions() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,"
                    + ENVIRONMENT_JSON
                    + ",\"skills\":[{\"name\":\"dev\",\"description\":\"Developer rules\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":0,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":3,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":\"2\","
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":null,\"sourceSetVersion\":0,"
                    + "\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2," + ENVIRONMENT_JSON + ",\"skills\":[]}"));
  }

  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(payload("\"secret\":\"x\",", "")));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "") + " x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\",\"extra\":\"x\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"one\",\"note\":\"two\","
                    + "\"rootPath\":\"/home/dev\"},\"sourceSetVersion\":0,"
                    + "\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"n\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[{\"sourceId\":\""
                    + SOURCE_ID
                    + "\",\"sourceVersion\":1,\"sourceRevision\":\""
                    + REVISION
                    + "\",\"skills\":[],\"diagnostics\":[],\"extra\":true}]}"));
  }

  @Test
  void rejectsMissingEnvironmentFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"n\"},\"sourceSetVersion\":0,"
                    + "\"skillSources\":[]}"));
  }

  @Test
  void rejectsInvalidNestedCapabilityShapes() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,"
                    + "\"skillSources\":{}}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "null")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "{\"sourceId\":\"not-a-uuid\",\"sourceVersion\":1,\"sourceRevision\":\""
                        + REVISION
                        + "\",\"skills\":[],\"diagnostics\":[]}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "{\"sourceId\":\""
                        + SOURCE_ID
                        + "\",\"sourceVersion\":-1,\"sourceRevision\":\""
                        + REVISION
                        + "\",\"skills\":[],\"diagnostics\":[]}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "{\"sourceId\":\""
                        + SOURCE_ID
                        + "\",\"sourceVersion\":1,\"sourceRevision\":\"\","
                        + "\"skills\":[],\"diagnostics\":[]}")));
  }

  /** skill 的 baseDirectory/revision 必须存在且合法；缺少 contentRevision 的旧形状被拒绝。 */
  @Test
  void rejectsInvalidSkillDescriptors() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "{\"sourceId\":\""
                        + SOURCE_ID
                        + "\",\"sourceVersion\":1,\"sourceRevision\":\""
                        + REVISION
                        + "\",\"skills\":[{\"sourceId\":\""
                        + SOURCE_ID
                        + "\",\"sourceVersion\":1,\"name\":\"dev\","
                        + "\"description\":\"Developer rules\","
                        + "\"baseDirectory\":\"/home/dev/.agents/skills/dev\"}],"
                        + "\"diagnostics\":[]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillDescriptor(
                SOURCE_ID, 1, "dev", "Developer rules", "relative/path", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkillDescriptor(SOURCE_ID, 1, "dev", "Developer rules", "/abs", "ABCDEF"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillDescriptor(
                SOURCE_ID, 1, "dev\nother", "Developer rules", "/abs", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillDescriptor(
                SOURCE_ID, 1, "dev", "Developer\u0000rules", "/abs", REVISION));
  }

  /** 来源 revision 只能是 PATH 聚合 SHA-256 或 Git 完整 commit id，任意标签文本不能进入持久快照。 */
  @Test
  void rejectsNonCanonicalSourceRevision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkillSourceSnapshot(SOURCE_ID, 1, "main", List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID, 1, REVISION.toUpperCase(), List.of(), List.of()));
  }

  /** 同一来源内重复 name 与跨来源同名都必须在构造边界失败。 */
  @Test
  void rejectsDuplicateSkillNames() {
    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(SOURCE_ID, 1, "same", "one", "/abs", REVISION);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID, 1, REVISION, List.of(skill, skill), List.of()));
    UUID otherSourceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                ENVIRONMENT,
                SOURCE_SET_VERSION,
                List.of(
                    new DaemonSkillSourceSnapshot(
                        SOURCE_ID, 1, REVISION, List.of(skill), List.of()),
                    new DaemonSkillSourceSnapshot(
                        otherSourceId,
                        1,
                        REVISION,
                        List.of(
                            new DaemonSkillDescriptor(
                                otherSourceId, 1, "same", "two", "/abs2", REVISION)),
                        List.of()))));
  }

  @Test
  void rejectsDuplicateSourceIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                ENVIRONMENT,
                SOURCE_SET_VERSION,
                List.of(
                    new DaemonSkillSourceSnapshot(SOURCE_ID, 1, REVISION, List.of(), List.of()),
                    new DaemonSkillSourceSnapshot(SOURCE_ID, 2, REVISION, List.of(), List.of()))));
  }

  /** skill descriptor 的 sourceId/sourceVersion 必须与所属来源一致。 */
  @Test
  void rejectsSkillBelongingToAnotherSource() {
    UUID otherSourceId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID,
                1,
                REVISION,
                List.of(new DaemonSkillDescriptor(otherSourceId, 1, "dev", "d", "/abs", REVISION)),
                List.of()));
  }

  /** 展平 helper 只按来源顺序串接 descriptor，不做任何按名覆盖。 */
  @Test
  void flattensSkillsInSourceOrder() {
    DaemonCapabilities capabilities =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            ENVIRONMENT,
            SOURCE_SET_VERSION,
            List.of(
                new DaemonSkillSourceSnapshot(
                    SOURCE_ID,
                    1,
                    REVISION,
                    List.of(new DaemonSkillDescriptor(SOURCE_ID, 1, "a", "A", "/abs", REVISION)),
                    List.of())));

    assertEquals(
        List.of("a"),
        capabilities.flattenSkills().stream().map(DaemonSkillDescriptor::name).toList());
  }

  @Test
  void rejectsInvalidEnvironmentMetadata() {
    assertInvalidEnvironment("plan9", "UTC", "Local environment.");
    assertInvalidEnvironment("linux", "Not/AZone", "Linux environment.");
    assertInvalidEnvironment("linux", "UTC", "");
    assertInvalidEnvironment("linux", "UTC", " ");
    assertInvalidEnvironment("linux", "UTC", " leading");
    assertInvalidEnvironment("linux", "UTC", "trailing ");
    assertInvalidEnvironment("linux", "UTC", "first\nsecond");
    assertInvalidEnvironment("linux", "UTC", "first\u2028second");
    assertInvalidEnvironment("linux", "UTC", "control\u0000value");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1),
                "/home/dev"));
    assertInvalidRootPath("");
    assertInvalidRootPath(" ");
    assertInvalidRootPath(" /home/dev");
    assertInvalidRootPath("/home/dev ");
    assertInvalidRootPath("/home/dev\u0000x");
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  private static String payload(String prefix, String skillSources) {
    return "{\"version\":2,"
        + prefix
        + ENVIRONMENT_JSON
        + ",\"sourceSetVersion\":"
        + SOURCE_SET_VERSION
        + ",\"skillSources\":"
        + skillSources
        + "}";
  }

  private void assertInvalidEnvironment(String operatingSystem, String timeZone, String note) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", operatingSystem);
    environment.put("timeZone", timeZone);
    environment.put("note", note);
    environment.put("rootPath", "/home/dev");
    root.put("sourceSetVersion", SOURCE_SET_VERSION);
    root.putArray("skillSources");
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidRootPath(String rootPath) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("note", "Linux environment.");
    environment.put("rootPath", rootPath);
    root.put("sourceSetVersion", SOURCE_SET_VERSION);
    root.putArray("skillSources");
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }
}
