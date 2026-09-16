package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** READY capabilities codec 的严格版本、来源集合版本、来源快照、唯一性与 environment metadata 契约。 */
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

  /** 完整来源快照必须精确往返，且集合版本位于 environment 与 skillSources 之间。 */
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
        "{\"version\":1,"
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
        "{\"version\":1," + ENVIRONMENT_JSON + ",\"sourceSetVersion\":0,\"skillSources\":[]}",
        codec.encode(
            new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, 0, List.of())));
  }

  /** sourceSetVersion 是必填字段：缺失、负数与错误类型都在 wire 边界拒绝，不做缺省推断。 */
  @Test
  void rejectsMissingOrInvalidSourceSetVersion() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1," + ENVIRONMENT_JSON + ",\"skillSources\":[]}"));
    for (String invalid : new String[] {"-1", "1.5", "\"7\"", "null", "true", "{}", "[]"}) {
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":1,"
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
                "{\"version\":1,"
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
                "{\"version\":1,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}")
            .sourceSetVersion());
    assertEquals(
        Long.MAX_VALUE,
        codec
            .decode(
                "{\"version\":1,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":9223372036854775807,\"skillSources\":[]}")
            .sourceSetVersion());
    assertEquals(
        "{\"version\":1," + ENVIRONMENT_JSON + ",\"sourceSetVersion\":7,\"skillSources\":[]}",
        codec.encode(
            new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, 7, List.of())));
  }

  /** 顶层 skills 形状与所有非当前版本都必须被拒绝，不做双解码。 */
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
                "{\"version\":\"1\","
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":null,\"sourceSetVersion\":0,"
                    + "\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1," + ENVIRONMENT_JSON + ",\"skills\":[]}"));
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
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\",\"extra\":\"x\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"one\",\"note\":\"two\","
                    + "\"rootPath\":\"/home/dev\"},\"sourceSetVersion\":0,"
                    + "\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
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
                "{\"version\":1,\"environment\":{\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"},"
                    + "\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"n\"},\"sourceSetVersion\":0,"
                    + "\"skillSources\":[]}"));
  }

  @Test
  void rejectsInvalidNestedCapabilityShapes() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,"
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
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "UTC", "Note", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "UTC", "Note", " /home/dev"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "Note", "/home/dev\u0000x"));
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  /** 测试意图：测试模型构造时非当前 capabilities 协议版本被直接拒绝。 */
  @Test
  void rejectsUnsupportedCapabilitiesVersionInModel() {
    assertEquals(
        DaemonCapabilities.VERSION,
        new DaemonCapabilities(
                DaemonCapabilities.VERSION, ENVIRONMENT, SOURCE_SET_VERSION, List.of())
            .version());
    for (int unsupported : new int[] {0, 2, 3}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new DaemonCapabilities(unsupported, ENVIRONMENT, SOURCE_SET_VERSION, List.of()));
    }
  }

  /** 测试意图：测试来源数量超过 512 上限时，在模型构造与解码时均被严格拒绝。 */
  @Test
  void rejectsCapabilitiesExceedingMaxSources() {
    List<DaemonSkillSourceSnapshot> sources = new ArrayList<>();
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < DaemonCapabilities.MAX_SOURCES + 1; i++) {
      UUID id = UUID.randomUUID();
      sources.add(new DaemonSkillSourceSnapshot(id, 0, REVISION, List.of(), List.of()));
      if (i > 0) {
        sb.append(",");
      }
      sb.append("{\"sourceId\":\"")
          .append(id)
          .append("\",\"sourceVersion\":0,\"sourceRevision\":\"")
          .append(REVISION)
          .append("\",\"skills\":[],\"diagnostics\":[]}");
    }
    sb.append("]");

    IllegalArgumentException modelError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DaemonCapabilities(
                    DaemonCapabilities.VERSION, ENVIRONMENT, SOURCE_SET_VERSION, sources));
    assertTrue(modelError.getMessage().contains("skillSources must not exceed"));

    String payload = payload("", sb.toString());
    DaemonProtocolException codecError =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
    assertTrue(codecError.getMessage().contains("READY skillSources must not exceed"));
  }

  /** 测试意图：测试技能总数超过 4096 上限时，在模型构造与解码时均被严格拒绝。 */
  @Test
  void rejectsCapabilitiesExceedingMaxSkillsTotal() {
    UUID id1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID id2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    List<DaemonSkillDescriptor> skills1 = new ArrayList<>();
    StringBuilder skillsJson1 = new StringBuilder("[");
    for (int i = 0; i < 2049; i++) {
      skills1.add(new DaemonSkillDescriptor(id1, 0, "s1-" + i, "desc", "/abs", REVISION));
      if (i > 0) {
        skillsJson1.append(",");
      }
      skillsJson1
          .append("{\"sourceId\":\"")
          .append(id1)
          .append("\",\"sourceVersion\":0,\"name\":\"s1-")
          .append(i)
          .append("\",\"description\":\"desc\",\"baseDirectory\":\"/abs\",\"contentRevision\":\"")
          .append(REVISION)
          .append("\"}");
    }
    skillsJson1.append("]");

    List<DaemonSkillDescriptor> skills2 = new ArrayList<>();
    StringBuilder skillsJson2 = new StringBuilder("[");
    for (int i = 0; i < 2048; i++) {
      skills2.add(new DaemonSkillDescriptor(id2, 0, "s2-" + i, "desc", "/abs", REVISION));
      if (i > 0) {
        skillsJson2.append(",");
      }
      skillsJson2
          .append("{\"sourceId\":\"")
          .append(id2)
          .append("\",\"sourceVersion\":0,\"name\":\"s2-")
          .append(i)
          .append("\",\"description\":\"desc\",\"baseDirectory\":\"/abs\",\"contentRevision\":\"")
          .append(REVISION)
          .append("\"}");
    }
    skillsJson2.append("]");

    DaemonSkillSourceSnapshot snap1 =
        new DaemonSkillSourceSnapshot(id1, 0, REVISION, skills1, List.of());
    DaemonSkillSourceSnapshot snap2 =
        new DaemonSkillSourceSnapshot(id2, 0, REVISION, skills2, List.of());

    IllegalArgumentException modelError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DaemonCapabilities(
                    DaemonCapabilities.VERSION,
                    ENVIRONMENT,
                    SOURCE_SET_VERSION,
                    List.of(snap1, snap2)));
    assertTrue(
        modelError.getMessage().contains("must not exceed " + DaemonCapabilities.MAX_SKILLS));

    String payload =
        payload(
            "",
            "[{\"sourceId\":\""
                + id1
                + "\",\"sourceVersion\":0,\"sourceRevision\":\""
                + REVISION
                + "\",\"skills\":"
                + skillsJson1
                + ",\"diagnostics\":[]},"
                + "{\"sourceId\":\""
                + id2
                + "\",\"sourceVersion\":0,\"sourceRevision\":\""
                + REVISION
                + "\",\"skills\":"
                + skillsJson2
                + ",\"diagnostics\":[]}]");
    DaemonProtocolException codecError =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
    assertTrue(codecError.getMessage().contains("READY skills must not exceed"));
  }

  /** 测试意图：测试解码时跨来源同名冲突被正确捕获并抛出 DaemonProtocolException。 */
  @Test
  void rejectsDuplicateSkillNamesAcrossSourcesInDecode() {
    UUID id1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID id2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    String payload =
        payload(
            "",
            "[{\"sourceId\":\""
                + id1
                + "\",\"sourceVersion\":0,\"sourceRevision\":\""
                + REVISION
                + "\",\"skills\":[{\"sourceId\":\""
                + id1
                + "\",\"sourceVersion\":0,\"name\":\"same-skill\",\"description\":\"d\",\"baseDirectory\":\"/abs\",\"contentRevision\":\""
                + REVISION
                + "\"}],\"diagnostics\":[]},"
                + "{\"sourceId\":\""
                + id2
                + "\",\"sourceVersion\":0,\"sourceRevision\":\""
                + REVISION
                + "\",\"skills\":[{\"sourceId\":\""
                + id2
                + "\",\"sourceVersion\":0,\"name\":\"same-skill\",\"description\":\"d\",\"baseDirectory\":\"/abs\",\"contentRevision\":\""
                + REVISION
                + "\"}],\"diagnostics\":[]}]");
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
    assertTrue(
        error.getMessage().contains("duplicate READY skill name across sources: same-skill"));
  }

  /** 测试意图：测试解码时重复来源 ID 被立即捕获并拒绝。 */
  @Test
  void rejectsDuplicateSourceIdsInDecode() {
    UUID id1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    String payload =
        payload(
            "",
            "[{\"sourceId\":\""
                + id1
                + "\",\"sourceVersion\":0,\"sourceRevision\":\""
                + REVISION
                + "\",\"skills\":[],\"diagnostics\":[]},"
                + "{\"sourceId\":\""
                + id1
                + "\",\"sourceVersion\":1,\"sourceRevision\":\""
                + REVISION
                + "\",\"skills\":[],\"diagnostics\":[]}]");
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
    assertTrue(error.getMessage().contains("duplicate READY skill source: " + id1));
  }

  private static String payload(String prefix, String skillSources) {
    return "{\"version\":1,"
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
