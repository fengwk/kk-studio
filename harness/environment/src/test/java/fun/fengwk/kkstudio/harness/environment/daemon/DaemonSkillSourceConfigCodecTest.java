package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

/** Skill 来源配置值对象与严格 codec 的字段形状、类型互斥、扫描路径安全与错误脱敏契约。 */
class DaemonSkillSourceConfigCodecTest {

  private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_SOURCE_ID =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String REVISION = "b".repeat(64);

  private final DaemonSkillSourceConfigCodec codec = new DaemonSkillSourceConfigCodec();

  /** PATH 来源只带 path/defaultSource，完整字段精确往返，且缺席的类型专属字段不输出显式 null。 */
  @Test
  void roundTripsPathSourceConfig() {
    DaemonSkillSourceConfig original =
        DaemonSkillSourceConfig.path(
            SOURCE_ID, 4, 9, "~/.agents/skills", true, Set.of(SOURCE_ID, OTHER_SOURCE_ID));

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertTrue(encoded.contains("\"type\":\"path\""), encoded);
    assertTrue(encoded.contains("\"sourceSetVersion\":9"), encoded);
    assertTrue(encoded.contains("\"defaultSource\":true"), encoded);
    // activeSourceIds 稳定排序输出，保证同一配置的 wire 文本可复现。
    assertTrue(
        encoded.contains(
            "\"activeSourceIds\":[\"11111111-1111-1111-1111-111111111111\","
                + "\"22222222-2222-2222-2222-222222222222\"]"),
        encoded);
    // canonical 编码省略缺席字段：既不输出显式 null，也不会被误读为“有值”。
    assertFalse(encoded.contains("\"url\""), encoded);
    assertFalse(encoded.contains("\"scanPath\""), encoded);
    assertFalse(encoded.contains("\"ref\""), encoded);
    assertFalse(encoded.contains("null"), encoded);
  }

  /** 缺席的类型专属字段可以显式 null 或整体缺省解码，两种 wire 都归一为同一配置。 */
  @Test
  void decodesAbsentOrNullOptionalFields() {
    DaemonSkillSourceConfig expected =
        DaemonSkillSourceConfig.path(SOURCE_ID, 4, 9, "/srv/skills", false, Set.of(SOURCE_ID));

    DaemonSkillSourceConfig omitted =
        codec.decode(
            "{\"sourceId\":\""
                + SOURCE_ID
                + "\",\"sourceVersion\":4,\"sourceSetVersion\":9,\"type\":\"path\","
                + "\"path\":\"/srv/skills\",\"defaultSource\":false,"
                + "\"activeSourceIds\":[\""
                + SOURCE_ID
                + "\"]}");

    assertEquals(expected, omitted);
    // 显式 null 与缺席等价：两种 wire 归一为同一配置。
    assertEquals(
        DaemonSkillSourceConfig.path(SOURCE_ID, 1, 1, "/srv/skills", false, Set.of(SOURCE_ID)),
        codec.decode(baseJson()));
    assertEquals(omitted, codec.decode(codec.encode(omitted)));
  }

  /** 非负的 sourceSetVersion 参与严格校验：负数、缺失与错误类型都必须被拒绝。 */
  @Test
  void requiresNonNegativeSourceSetVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonSkillSourceConfig.path(
                SOURCE_ID, 1, -1, "/srv/skills", false, Set.of(SOURCE_ID)));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode(baseJson().replace("\"sourceSetVersion\":1", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(baseJson().replace("\"sourceSetVersion\":1", "\"sourceSetVersion\":-1")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                baseJson().replace("\"sourceSetVersion\":1", "\"sourceSetVersion\":\"1\"")));
  }

  /** GIT 来源携带 url/ref/scanPath 与上次已应用 revision。 */
  @Test
  void roundTripsGitSourceConfig() {
    DaemonSkillSourceConfig original =
        DaemonSkillSourceConfig.git(
            SOURCE_ID,
            7,
            11,
            "ssh://git.example/repo.git",
            "v1.2.0",
            "skills",
            REVISION,
            Set.of(SOURCE_ID));

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertTrue(encoded.contains("\"sourceSetVersion\":11"), encoded);
    assertFalse(encoded.contains("\"path\""), encoded);
    assertTrue(encoded.contains("\"defaultSource\":false"), encoded);
  }

  /** ref 为 null 时表示跟踪远端默认 HEAD；空 scanPath 表示仓库根；两者都不进入 canonical wire。 */
  @Test
  void acceptsGitSourceWithoutRef() {
    DaemonSkillSourceConfig config =
        DaemonSkillSourceConfig.git(
            SOURCE_ID, 0, 3, "https://git.example/repo.git", null, null, null, Set.of(SOURCE_ID));

    String encoded = codec.encode(config);

    assertEquals(config, codec.decode(encoded));
    assertFalse(encoded.contains("\"ref\""), encoded);
    assertFalse(encoded.contains("\"scanPath\""), encoded);
  }

  /** 显式 "." 与缺省 scanPath 都表示仓库根，canonical 编码不得输出无法被自身解码的空字符串。 */
  @Test
  void normalizesRepositoryRootScanPathToAbsent() {
    DaemonSkillSourceConfig config =
        DaemonSkillSourceConfig.git(
            SOURCE_ID, 0, 3, "https://git.example/repo.git", null, ".", null, Set.of(SOURCE_ID));

    String encoded = codec.encode(config);

    assertNull(config.scanPath());
    assertFalse(encoded.contains("\"scanPath\""), encoded);
    assertEquals(config, codec.decode(encoded));
  }

  /** 类型与字段互斥：PATH 不得携带 GIT 字段，GIT 不得携带 path/defaultSource。 */
  @Test
  void rejectsFieldsForTheWrongSourceType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceConfig(
                SOURCE_ID,
                1,
                1,
                DaemonSkillSourceType.PATH,
                "/srv/skills",
                false,
                "https://git.example/repo.git",
                null,
                null,
                null,
                Set.of(SOURCE_ID)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceConfig(
                SOURCE_ID,
                1,
                1,
                DaemonSkillSourceType.GIT,
                null,
                true,
                "https://git.example/repo.git",
                null,
                null,
                null,
                Set.of(SOURCE_ID)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceConfig(
                SOURCE_ID,
                1,
                1,
                DaemonSkillSourceType.GIT,
                "/srv/skills",
                false,
                "https://git.example/repo.git",
                null,
                null,
                null,
                Set.of(SOURCE_ID)));
  }

  /** activeSourceIds 必须包含被操作的来源，且不为空。 */
  @Test
  void requiresActiveSourceIdsToContainTheOperatedSource() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonSkillSourceConfig.path(SOURCE_ID, 1, 1, "/srv/skills", false, Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonSkillSourceConfig.path(
                SOURCE_ID, 1, 1, "/srv/skills", false, Set.of(OTHER_SOURCE_ID)));
  }

  /** scanPath 只接受仓库内相对路径：绝对路径、盘符与 `..` 遍历都拒绝。 */
  @Test
  void rejectsUnsafeScanPaths() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonSkillSourceConfig.repositoryRelativePath("/abs"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonSkillSourceConfig.repositoryRelativePath("\\abs"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonSkillSourceConfig.repositoryRelativePath("C:/repo"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonSkillSourceConfig.repositoryRelativePath("../outside"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonSkillSourceConfig.repositoryRelativePath("skills/../../outside"));
    assertEquals("skills", DaemonSkillSourceConfig.repositoryRelativePath("skills"));
    assertEquals("", DaemonSkillSourceConfig.repositoryRelativePath("."));
    assertEquals("skills/sub", DaemonSkillSourceConfig.repositoryRelativePath("skills\\sub"));
  }

  /** 未知字段、错误类型、非 canonical UUID 与重复 activeSourceIds 都必须在 wire 边界被拒绝。 */
  @Test
  void rejectsMalformedWireConfig() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(encodeWith("\"unexpected\":true,")));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(encodeWith("\"sourceVersion\":\"1\",")));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(encodeWith("\"type\":\"ftp\",")));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode(baseJson().replace(SOURCE_ID.toString(), "not-a-uuid")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                baseJson()
                    .replace(
                        "\"activeSourceIds\":[\"" + SOURCE_ID + "\"]",
                        "\"activeSourceIds\":[\"" + SOURCE_ID + "\",\"" + SOURCE_ID + "\"]")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                baseJson()
                    .replace(
                        "\"activeSourceIds\":[\"" + SOURCE_ID + "\"]", "\"activeSourceIds\":[]")));
  }

  /** 异常文本不得包含 URL/ref 值，避免把 Git 凭据带进错误输出。 */
  @Test
  void doesNotEchoConfiguredLocationValuesInErrors() {
    String url = "https://user:secret-token@git.example/repo.git";
    String json =
        "{\"sourceId\":\""
            + SOURCE_ID
            + "\",\"sourceVersion\":1,\"sourceSetVersion\":1,\"type\":\"git\","
            + "\"path\":null,\"defaultSource\":false,\"url\":\""
            + url
            + "\",\"ref\":null,\"scanPath\":null,\"currentlyAppliedRevision\":null,"
            + "\"activeSourceIds\":[\""
            + SOURCE_ID
            + "\"]}";

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decode(json.replace("\"sourceVersion\":1", "\"sourceVersion\":\"1\"")));

    assertFalse(error.getMessage().contains("secret-token"), error.getMessage());
  }

  private static String baseJson() {
    return "{\"sourceId\":\""
        + SOURCE_ID
        + "\",\"sourceVersion\":1,\"sourceSetVersion\":1,\"type\":\"path\","
        + "\"path\":\"/srv/skills\",\"defaultSource\":false,\"url\":null,\"ref\":null,"
        + "\"scanPath\":null,\"currentlyAppliedRevision\":null,\"activeSourceIds\":[\""
        + SOURCE_ID
        + "\"]}";
  }

  private static String encodeWith(String prefix) {
    return baseJson().replace("{\"sourceId\"", "{" + prefix + "\"sourceId\"");
  }
}
