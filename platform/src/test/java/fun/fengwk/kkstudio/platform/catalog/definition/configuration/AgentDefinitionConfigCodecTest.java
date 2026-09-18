package fun.fengwk.kkstudio.platform.catalog.definition.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;

import java.util.ArrayList;
import java.util.List;

class AgentDefinitionConfigCodecTest {

  private static final String SOURCE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SOURCE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  private final AgentDefinitionConfigCodec codec =
      new AgentDefinitionConfigCodec(new ObjectMapper());

  /** 测试意图：验证完整的 AgentDefinitionConfigDTO（含规范结构化 skills 引用）能够正确往返编解码并保持元素顺序。 */
  @Test
  void roundTripsCompleteConfigAndPreservesOrder() {
    AgentDefinitionConfigDTO config = config();
    config.setTools(List.of("read"));
    config.setSkills(
        List.of(new AgentSkillRefDTO(SOURCE_A, "dev"), new AgentSkillRefDTO(SOURCE_B, "ops")));
    config.setSubagents(List.of("reviewer"));

    String encoded = codec.encode(config);
    AgentDefinitionConfigDTO decoded = codec.decode(encoded);

    assertEquals(List.of("read"), decoded.getTools());
    assertEquals(
        List.of(new AgentSkillRefDTO(SOURCE_A, "dev"), new AgentSkillRefDTO(SOURCE_B, "ops")),
        decoded.getSkills());
    assertEquals(List.of("reviewer"), decoded.getSubagents());
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
    List<AgentSkillRefDTO> skillsWithNull = new ArrayList<>();
    skillsWithNull.add(new AgentSkillRefDTO(SOURCE_A, "dev"));
    skillsWithNull.add(null);
    config.setSkills(skillsWithNull);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(config));
  }

  /** 测试意图：验证 sourceId 必须为小写 canonical UUID，拒绝大写字母、空白以及非 UUID 格式。 */
  @Test
  void rejectsNonCanonicalAndMalformedSourceId() {
    AgentDefinitionConfigDTO upper = config();
    upper.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A.toUpperCase(), "dev")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(upper));

    AgentDefinitionConfigDTO blank = config();
    blank.setSkills(List.of(new AgentSkillRefDTO("   ", "dev")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(blank));

    AgentDefinitionConfigDTO malformed = config();
    malformed.setSkills(List.of(new AgentSkillRefDTO("not-a-uuid", "dev")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(malformed));
  }

  /** 测试意图：验证 Skill 名称必须是规范短名，拒绝空白、首尾空格、超长以及包含非法分隔符或控制字符的名称。 */
  @Test
  void rejectsInvalidCanonicalShortNames() {
    AgentDefinitionConfigDTO blankName = config();
    blankName.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, " ")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(blankName));

    AgentDefinitionConfigDTO padded = config();
    padded.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, " dev ")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(padded));

    AgentDefinitionConfigDTO slash = config();
    slash.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, "my/skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(slash));

    AgentDefinitionConfigDTO colon = config();
    colon.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, "my:skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(colon));

    AgentDefinitionConfigDTO at = config();
    at.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, "my@skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(at));

    AgentDefinitionConfigDTO backslash = config();
    backslash.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, "my\\skill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(backslash));

    AgentDefinitionConfigDTO control = config();
    control.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, "my\nskill")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(control));

    AgentDefinitionConfigDTO tooLong = config();
    tooLong.setSkills(List.of(new AgentSkillRefDTO(SOURCE_A, "a".repeat(129))));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(tooLong));
  }

  /** 测试意图：验证 skills 引用严格拒绝重复的 (sourceId, name) 标识组合。 */
  @Test
  void rejectsDuplicateSkillIdentities() {
    AgentDefinitionConfigDTO duplicate = config();
    duplicate.setSkills(
        List.of(new AgentSkillRefDTO(SOURCE_A, "dev"), new AgentSkillRefDTO(SOURCE_A, "dev")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(duplicate));

    // 不同 sourceId 的同名在纯 Codec 层允许（Environment 唯一性由 referenceResolver 校验）
    AgentDefinitionConfigDTO differentSources = config();
    differentSources.setSkills(
        List.of(new AgentSkillRefDTO(SOURCE_A, "dev"), new AgentSkillRefDTO(SOURCE_B, "dev")));
    AgentDefinitionConfigDTO decoded = codec.decode(codec.encode(differentSources));
    assertEquals(2, decoded.getSkills().size());
  }

  /** 测试意图：验证严格拒绝旧的字符串数组 skills 格式（无容错旧字符串解码）。 */
  @Test
  void rejectsLegacyStringArraySkills() {
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
}
