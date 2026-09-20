package fun.fengwk.kkstudio.platform.catalog.definition.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.ArrayList;
import java.util.List;

class AgentDefinitionConfigCodecTest {

  private final AgentDefinitionConfigCodec codec =
      new AgentDefinitionConfigCodec(new ObjectMapper());

  /** 测试意图：验证完整配置（含全局 Skill 引用）能够正确往返编解码并保持元素顺序。 */
  @Test
  void roundTripsCompleteConfigAndPreservesOrder() {
    AgentDefinitionConfigDTO config = config();
    config.setTools(List.of("read"));
    config.setSkills(List.of(skillRef("tools", "dev"), skillRef("ops-tools", "ops")));
    config.setSubagents(List.of("reviewer"));
    config.setInheritParentEnvironment(false);

    String encoded = codec.encode(config);
    AgentDefinitionConfigDTO decoded = codec.decode(encoded);

    assertEquals(List.of("read"), decoded.getTools());
    assertEquals(
        List.of(skillRef("tools", "dev"), skillRef("ops-tools", "ops")), decoded.getSkills());
    assertEquals(List.of("reviewer"), decoded.getSubagents());
    assertEquals(Boolean.FALSE, decoded.getInheritParentEnvironment());
  }

  /**
   * 测试意图：验证 inheritParentEnvironment 的严格契约——缺省（字段缺失）解析为 true，序列化总是显式输出该字段，而显式 JSON null 属于非法 shape
   * 并 fail closed。
   */
  @Test
  void defaultsInheritParentEnvironmentToTrueAndRejectsExplicitNull() {
    // 缺省：持久化 JSON 不含该字段时按 DTO 初值得到 true。
    AgentDefinitionConfigDTO omitted =
        codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[]}");
    assertEquals(Boolean.TRUE, omitted.getInheritParentEnvironment());

    // 编码始终显式输出该字段，使当前持久化形状自描述。
    String encoded = codec.encode(config());
    assertTrue(encoded.contains("\"inheritParentEnvironment\":true"), encoded);

    // 显式 null 不是缺省而是非法 shape：decode 走 stored config 严格边界。
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":null}"));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":\"false\"}"));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":0}"));
    // encode 同样拒绝 null 值的 config 对象。
    AgentDefinitionConfigDTO nullSwitch = config();
    nullSwitch.setInheritParentEnvironment(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(nullSwitch));
  }

  /** 测试意图：验证编解码时严格要求必填字段，拒绝 null 字段与非规范配置。 */
  @Test
  void rejectsIncompleteAndNonCanonicalTypedConfigs() {
    assertThrows(IllegalArgumentException.class, () -> codec.encode(null));

    AgentDefinitionConfigDTO missingTools = config();
    missingTools.setTools(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingTools));

    AgentDefinitionConfigDTO missingSkills = config();
    missingSkills.setSkills(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingSkills));

    AgentDefinitionConfigDTO missingSubagents = config();
    missingSubagents.setSubagents(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingSubagents));

    AgentDefinitionConfigDTO whitespace = config();
    whitespace.setTools(List.of(" read "));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(whitespace));
  }

  /** 测试意图：验证 skills 引用列表拒绝包含 null 元素。 */
  @Test
  void rejectsNullElementsInSkills() {
    AgentDefinitionConfigDTO config = config();
    List<SkillRefDTO> skillsWithNull = new ArrayList<>();
    skillsWithNull.add(skillRef("tools", "dev"));
    skillsWithNull.add(null);
    config.setSkills(skillsWithNull);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(config));
  }

  /** 测试意图：验证 Skill 名称与包名必须是规范短名，拒绝空白、首尾空格、超长以及包含非法分隔符或控制字符的名称。 */
  @Test
  void rejectsInvalidCanonicalShortNames() {
    AgentDefinitionConfigDTO blankName = config();
    blankName.setSkills(List.of(skillRef("tools", " ")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(blankName));

    AgentDefinitionConfigDTO padded = config();
    padded.setSkills(List.of(skillRef("tools", " dev ")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(padded));

    AgentDefinitionConfigDTO slash = config();
    slash.setSkills(List.of(skillRef("tools", "my/skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(slash));

    AgentDefinitionConfigDTO colon = config();
    colon.setSkills(List.of(skillRef("tools", "my:skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(colon));

    AgentDefinitionConfigDTO at = config();
    at.setSkills(List.of(skillRef("tools", "my@skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(at));

    AgentDefinitionConfigDTO backslash = config();
    backslash.setSkills(List.of(skillRef("tools", "my\\skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(backslash));

    AgentDefinitionConfigDTO control = config();
    control.setSkills(List.of(skillRef("tools", "my\nskill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(control));

    AgentDefinitionConfigDTO tooLong = config();
    tooLong.setSkills(List.of(skillRef("tools", "a".repeat(129))));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(tooLong));

    AgentDefinitionConfigDTO invalidPkg = config();
    invalidPkg.setSkills(List.of(skillRef("my/pkg", "dev")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidPkg));
  }

  /** 测试意图：验证全局 Skill 引用严格拒绝重复。 */
  @Test
  void rejectsDuplicateSkillIdentities() {
    AgentDefinitionConfigDTO duplicate = config();
    duplicate.setSkills(List.of(skillRef("tools", "dev"), skillRef("tools", "dev")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(duplicate));
  }

  /** 测试意图：验证严格拒绝旧的结构化 Environment Skill 引用格式。 */
  @Test
  void rejectsLegacyStructuredSkillReferences() {
    String legacy =
        "{\"tools\":[],\"skills\":[{\"sourceId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
            + "\"name\":\"dev\"}],\"subagents\":[]}";
    assertThrows(IllegalStateException.class, () -> codec.decode(legacy));
  }

  /** 测试意图：验证旧的裸字符串 Skill 引用格式被严格拒绝。 */
  @Test
  void rejectsLegacyStringSkillReferences() {
    String legacy = "{\"tools\":[],\"skills\":[\"dev\"],\"subagents\":[]}";
    assertThrows(IllegalStateException.class, () -> codec.decode(legacy));
  }

  /** 测试意图：验证工具列表的旧 wire 字段 toolIds 被严格拒绝，不存在任何兼容读取路径。 */
  @Test
  void rejectsLegacyToolIdsField() {
    String legacy = "{\"toolIds\":[\"read\"],\"skills\":[],\"subagents\":[]}";
    assertThrows(IllegalStateException.class, () -> codec.decode(legacy));
  }

  /** 持久化 JSON 的已知顶层字段必须禁止重复，避免重复键的解析结果依赖 Jackson 行为。 */
  @Test
  void rejectsDuplicateKnownTopLevelFields() {
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"tools\":[],\"skills\":[],\"subagents\":[]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"skills\":[],\"subagents\":[]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[],\"subagents\":[]}"));
  }

  @Test
  void rejectsMalformedUnknownCoercedAndTrailingJson() {
    assertThrows(IllegalStateException.class, () -> codec.decode(null));
    assertThrows(IllegalStateException.class, () -> codec.decode(" "));
    assertThrows(IllegalStateException.class, () -> codec.decode("null"));
    assertThrows(IllegalStateException.class, () -> codec.decode("{}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[],\"unknown\":true}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[\"read\"],\"skills\":[],\"subagents\":[42]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[42],\"skills\":[],\"subagents\":[]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[]} {}"));
  }

  private static AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    return config;
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }
}
