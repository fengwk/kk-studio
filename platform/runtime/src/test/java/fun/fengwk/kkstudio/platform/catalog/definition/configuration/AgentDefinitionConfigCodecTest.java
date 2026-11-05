package fun.fengwk.kkstudio.platform.catalog.definition.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.skill.SkillRefDTO;

import java.util.ArrayList;
import java.util.List;

class AgentDefinitionConfigCodecTest {

  private final AgentDefinitionConfigCodec codec =
      new AgentDefinitionConfigCodec(JsonMapper.builder().build());

  /** 测试意图：验证完整配置（含全局 Skill 引用）能够正确往返编解码并保持元素顺序。 */
  @Test
  void roundTripsCompleteConfigAndPreservesOrder() {
    AgentDefinitionConfigDTO config = config();
    config.setTools(List.of("read"));
    config.setSkills(List.of(skillRef("tools", "dev"), skillRef("ops-tools", "ops")));
    config.setSubagents(List.of("reviewer"));

    String encoded = codec.encode(config);
    AgentDefinitionConfigDTO decoded = codec.decode(encoded);

    assertEquals(List.of("read"), decoded.getTools());
    assertEquals(
        List.of(skillRef("tools", "dev"), skillRef("ops-tools", "ops")), decoded.getSkills());
    assertEquals(List.of("reviewer"), decoded.getSubagents());
  }

  /** 测试意图：旧环境开关不再属于配置契约，持久化配置必须拒绝该字段。 */
  @Test
  void rejectsLegacyEnvironmentField() {
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":true}"));
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

  /** 技能引用必须是合法对象结构，拒绝字符串或非法的结构。 */
  @Test
  void rejectsMalformedSkillReferences() {
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[\"dev\"],\"subagents\":[]}"));
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
