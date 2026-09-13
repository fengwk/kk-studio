package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillManifest.RetainedSkill;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link DaemonSkillManifestCodec}、{@link DaemonSkillManifest} 与 {@link DaemonSkill} 测试：
 * 验证编解码双向一致性、严格解码拒绝（未知字段、类型错误、非 canonical UUID、重复条目）、 manifest 构造期不变量约束以及 DaemonSkill 基础模型约束。
 */
class DaemonSkillManifestCodecTest {

  private static final UUID SOURCE_ID = DaemonSkillTestSupport.SOURCE_ID;
  private static final UUID OTHER_SOURCE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000002");
  private static final String REVISION = "a".repeat(64);
  private static final String BASE_DIR = "/srv/skills/alpha";

  /** 测试意图：合法的 manifest 编码后可被无损解码还原。 */
  @Test
  void encodesAndDecodesManifestRoundTrip() {
    DaemonSkillDescriptor descriptor =
        new DaemonSkillDescriptor(SOURCE_ID, 1, "alpha", "Alpha skill", BASE_DIR, REVISION);
    DaemonSkillSourceSnapshot source =
        new DaemonSkillSourceSnapshot(SOURCE_ID, 1, REVISION, List.of(descriptor), List.of());
    RetainedSkill retained = new RetainedSkill(SOURCE_ID, "alpha", REVISION, BASE_DIR);

    DaemonSkillManifest original =
        new DaemonSkillManifest(1, 5L, List.of(SOURCE_ID), List.of(source), List.of(retained));

    String json = DaemonSkillManifestCodec.encode(original);
    assertNotNull(json);

    DaemonSkillManifest decoded = DaemonSkillManifestCodec.decode(json);
    assertEquals(original.version(), decoded.version());
    assertEquals(original.sourceSetVersion(), decoded.sourceSetVersion());
    assertEquals(original.activeSourceIds(), decoded.activeSourceIds());
    assertEquals(original.sources(), decoded.sources());
    assertEquals(original.retained(), decoded.retained());
  }

  /** 测试意图：解码必须严格拒绝格式错误、非对象根节点以及意外的顶层字段。 */
  @Test
  void decodeRejectsMalformedNonObjectOrUnexpectedRootFields() {
    // 畸形 JSON
    assertThrows(
        IllegalStateException.class, () -> DaemonSkillManifestCodec.decode("{broken-json"));

    // 根节点为非对象（数组、字符串、数值）
    for (String nonObject : List.of("[1, 2]", "\"string\"", "123", "true")) {
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () -> DaemonSkillManifestCodec.decode(nonObject),
              "expected rejection for: " + nonObject);
      assertTrue(error.getMessage().contains("must be a JSON object"));
    }

    // 意外顶层字段
    String unexpectedFieldJson =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [],
          "retained": [],
          "unknownField": "bad"
        }
        """;
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> DaemonSkillManifestCodec.decode(unexpectedFieldJson));
    assertTrue(error.getMessage().contains("unexpected skill manifest field"));
  }

  /** 测试意图：解码必须校验 version、sourceSetVersion、activeSourceIds 的格式、类型与规范性。 */
  @Test
  void decodeRejectsInvalidVersionSourceSetOrActiveSources() {
    // version 缺失或非整数
    for (String badVersion :
        List.of(
            "{\"version\": \"1\", \"sourceSetVersion\": 0, \"activeSourceIds\": [], \"sources\": [], \"retained\": []}",
            "{\"version\": 1.5, \"sourceSetVersion\": 0, \"activeSourceIds\": [], \"sources\": [], \"retained\": []}",
            "{\"sourceSetVersion\": 0, \"activeSourceIds\": [], \"sources\": [], \"retained\": []}")) {
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(badVersion));
      assertTrue(error.getMessage().contains("version must be an integer"));
    }

    // sourceSetVersion 负数或非整数
    String negativeSetVersion =
        """
        {
          "version": 1,
          "sourceSetVersion": -1,
          "activeSourceIds": [],
          "sources": [],
          "retained": []
        }
        """;
    IllegalStateException errorSet =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(negativeSetVersion));
    assertTrue(errorSet.getMessage().contains("sourceSetVersion must be a non-negative long"));

    // activeSourceIds 非数组
    String nonArrayActive =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": "not-array",
          "sources": [],
          "retained": []
        }
        """;
    IllegalStateException errorActive =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(nonArrayActive));
    assertTrue(errorActive.getMessage().contains("activeSourceIds must be an array"));

    // activeSourceIds 包含非文本或非规范 UUID
    for (String badEntry :
        List.of(
            "{\"version\": 1, \"sourceSetVersion\": 0, \"activeSourceIds\": [123], \"sources\": [], \"retained\": []}",
            "{\"version\": 1, \"sourceSetVersion\": 0, \"activeSourceIds\": [\"not-a-uuid\"], \"sources\": [], \"retained\": []}",
            "{\"version\": 1, \"sourceSetVersion\": 0, \"activeSourceIds\": [\"ABCDEF01-2345-4789-ABCD-EF0123456789\"], \"sources\": [], \"retained\": []}")) {
      assertThrows(IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(badEntry));
    }

    // activeSourceIds 包含重复 UUID
    String duplicateActive =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": ["%s", "%s"],
          "sources": [],
          "retained": []
        }
        """
            .formatted(SOURCE_ID, SOURCE_ID);
    IllegalStateException errorDup =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(duplicateActive));
    assertTrue(errorDup.getMessage().contains("duplicate skill manifest active source"));
  }

  /** 测试意图：解码必须校验 sources 数组、其元素形状与来源 ID 唯一性。 */
  @Test
  void decodeRejectsInvalidSourcesOrDuplicateSources() {
    // sources 非数组
    String nonArraySources =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": "bad",
          "retained": []
        }
        """;
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(nonArraySources));
    assertTrue(error.getMessage().contains("sources must be an array"));

    // sources 元素无效（如缺少必需字段）
    String invalidSourceElement =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [{"broken": true}],
          "retained": []
        }
        """;
    IllegalStateException errorElem =
        assertThrows(
            IllegalStateException.class,
            () -> DaemonSkillManifestCodec.decode(invalidSourceElement));
    assertTrue(errorElem.getMessage().contains("invalid skill manifest source"));

    // sources 包含重复来源 ID
    String validSourceJson =
        """
        {
          "sourceId": "%s",
          "sourceVersion": 1,
          "sourceRevision": "%s",
          "skills": [],
          "diagnostics": []
        }
        """
            .formatted(SOURCE_ID, REVISION);
    String duplicateSourceJson =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": ["%s"],
          "sources": [%s, %s],
          "retained": []
        }
        """
            .formatted(SOURCE_ID, validSourceJson, validSourceJson);
    IllegalStateException errorDupSource =
        assertThrows(
            IllegalStateException.class,
            () -> DaemonSkillManifestCodec.decode(duplicateSourceJson));
    assertTrue(errorDupSource.getMessage().contains("duplicate skill manifest source"));
  }

  /** 测试意图：解码必须校验 retained 数组、元素字段与身份唯一性。 */
  @Test
  void decodeRejectsInvalidRetainedEntriesOrDuplicateIdentities() {
    // retained 非数组
    String nonArrayRetained =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [],
          "retained": "bad"
        }
        """;
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(nonArrayRetained));
    assertTrue(error.getMessage().contains("retained must be an array"));

    // retained 元素非对象
    String nonObjectRetained =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [],
          "retained": ["not-object"]
        }
        """;
    IllegalStateException errorNonObj =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(nonObjectRetained));
    assertTrue(errorNonObj.getMessage().contains("must be an object"));

    // retained 元素含意外字段
    String unexpectedRetainedField =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [],
          "retained": [{
            "sourceId": "%s",
            "name": "alpha",
            "revision": "%s",
            "baseDirectory": "%s",
            "extra": 123
          }]
        }
        """
            .formatted(SOURCE_ID, REVISION, BASE_DIR);
    IllegalStateException errorExtra =
        assertThrows(
            IllegalStateException.class,
            () -> DaemonSkillManifestCodec.decode(unexpectedRetainedField));
    assertTrue(errorExtra.getMessage().contains("unexpected skill manifest.retained[0] field"));

    // retained 元素字段为 blank 文本
    String blankFieldRetained =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [],
          "retained": [{
            "sourceId": "%s",
            "name": "   ",
            "revision": "%s",
            "baseDirectory": "%s"
          }]
        }
        """
            .formatted(SOURCE_ID, REVISION, BASE_DIR);
    IllegalStateException errorBlank =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(blankFieldRetained));
    assertTrue(errorBlank.getMessage().contains("must be non-blank text"));

    // retained 包含重复身份 (sourceId, name, revision)
    String retainedEntry =
        """
        {
          "sourceId": "%s",
          "name": "alpha",
          "revision": "%s",
          "baseDirectory": "%s"
        }
        """
            .formatted(SOURCE_ID, REVISION, BASE_DIR);
    String duplicateRetained =
        """
        {
          "version": 1,
          "sourceSetVersion": 0,
          "activeSourceIds": [],
          "sources": [],
          "retained": [%s, %s]
        }
        """
            .formatted(retainedEntry, retainedEntry);
    IllegalStateException errorDupRetained =
        assertThrows(
            IllegalStateException.class, () -> DaemonSkillManifestCodec.decode(duplicateRetained));
    assertTrue(errorDupRetained.getMessage().contains("duplicate skill manifest retained entry"));
  }

  /** 测试意图：DaemonSkillManifest 构造函数强制版本号、来源集合与交叉不变量校验。 */
  @Test
  void daemonSkillManifestConstructorEnforcesCrossInvariants() {
    DaemonSkillDescriptor descriptor =
        new DaemonSkillDescriptor(SOURCE_ID, 1, "alpha", "desc", BASE_DIR, REVISION);
    DaemonSkillSourceSnapshot source =
        new DaemonSkillSourceSnapshot(SOURCE_ID, 1, REVISION, List.of(descriptor), List.of());
    RetainedSkill retained = new RetainedSkill(SOURCE_ID, "alpha", REVISION, BASE_DIR);

    // version 不等于 1
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillManifest(2, 0L, List.of(SOURCE_ID), List.of(source), List.of(retained)));

    // sourceSetVersion 为负数
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillManifest(
                1, -1L, List.of(SOURCE_ID), List.of(source), List.of(retained)));

    // activeSourceIds 包含重复元素
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillManifest(
                1, 0L, List.of(SOURCE_ID, SOURCE_ID), List.of(source), List.of(retained)));

    // published sources 不在 activeSourceIds 之中
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillManifest(
                1, 0L, List.of(OTHER_SOURCE_ID), List.of(source), List.of(retained)));

    // current descriptor 缺少 retained 记录
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkillManifest(1, 0L, List.of(SOURCE_ID), List.of(source), List.of()));

    // retained 记录的 baseDirectory 与 current descriptor 不一致
    RetainedSkill mismatchedBaseDir =
        new RetainedSkill(SOURCE_ID, "alpha", REVISION, "/other/path");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillManifest(
                1, 0L, List.of(SOURCE_ID), List.of(source), List.of(mismatchedBaseDir)));

    // 跨来源同名 skill 被拒绝
    DaemonSkillDescriptor otherDescriptorSameName =
        new DaemonSkillDescriptor(OTHER_SOURCE_ID, 1, "alpha", "desc 2", "/srv/other", REVISION);
    DaemonSkillSourceSnapshot otherSource =
        new DaemonSkillSourceSnapshot(
            OTHER_SOURCE_ID, 1, REVISION, List.of(otherDescriptorSameName), List.of());
    RetainedSkill otherRetained =
        new RetainedSkill(OTHER_SOURCE_ID, "alpha", REVISION, "/srv/other");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillManifest(
                1,
                0L,
                List.of(SOURCE_ID, OTHER_SOURCE_ID),
                List.of(source, otherSource),
                List.of(retained, otherRetained)));

    // 来源数量超出 MAX_SOURCES
    List<UUID> tooManySourceIds = new ArrayList<>();
    for (int i = 0; i <= DaemonCapabilities.MAX_SOURCES; i++) {
      tooManySourceIds.add(UUID.nameUUIDFromBytes(("src-" + i).getBytes()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkillManifest(1, 0L, tooManySourceIds, List.of(), List.of()));
  }

  /** 测试意图：DaemonSkill 构造函数必须校验 name 非空、description 非空与 baseDirectory 必须是绝对路径。 */
  @Test
  void daemonSkillConstructorEnforcesInvariants() {
    Path absPath = Path.of(BASE_DIR);

    // 正常构造与 descriptor 生成
    DaemonSkill skill = new DaemonSkill("alpha", "Alpha skill", absPath, REVISION, "# Body");
    assertEquals("alpha", skill.name());
    assertEquals("Alpha skill", skill.description());
    assertEquals(absPath, skill.baseDirectory());
    assertEquals(REVISION, skill.contentRevision());
    assertEquals("# Body", skill.body());

    DaemonSkillDescriptor descriptor = skill.descriptor(SOURCE_ID, 2L);
    assertEquals(SOURCE_ID, descriptor.sourceId());
    assertEquals(2L, descriptor.sourceVersion());
    assertEquals("alpha", descriptor.name());

    // 空白 name
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkill("   ", "desc", absPath, REVISION, "# Body"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkill(null, "desc", absPath, REVISION, "# Body"));

    // 空白 description
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkill("alpha", "   ", absPath, REVISION, "# Body"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkill("alpha", null, absPath, REVISION, "# Body"));

    // 非绝对 baseDirectory
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonSkill("alpha", "desc", Path.of("relative/path"), REVISION, "# Body"));
  }
}
