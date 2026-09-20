package fun.fengwk.kkstudio.share.ai.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;

/** Tool catalog 与 Agent 配置的 wire 契约：三态 EnvironmentSupport 与结构化 Skill 引用。 */
class ToolCatalogDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void toolCatalogEntryUsesEnvironmentSupportInsteadOfBooleanPair() throws Exception {
    // 意图：旧的 environmentRequired 布尔字段被三态枚举取代，唯一环境限定走可空 requiredEnvironmentId。
    assertThrows(
        NoSuchFieldException.class,
        () -> ToolCatalogEntryDTO.class.getDeclaredField("environmentRequired"));
    assertEquals(
        EnvironmentSupportDTO.class,
        ToolCatalogEntryDTO.class.getDeclaredField("environmentSupport").getType());
    assertEquals(
        String.class,
        ToolCatalogEntryDTO.class.getDeclaredField("requiredEnvironmentId").getType());

    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setName("read");
    dto.setDescription("Read a file.");
    dto.setEnvironmentSupport(EnvironmentSupportDTO.OPTIONAL);
    dto.setRequiredEnvironmentId(null);

    String json = MAPPER.writeValueAsString(dto);
    assertTrue(json.contains("\"environmentSupport\":\"OPTIONAL\""), json);
    assertThrows(Exception.class, () -> EnvironmentSupportDTO.valueOf("OPTIONAL_OR_REQUIRED"));
  }

  @Test
  void environmentSupportIsTheSameThreeStateEnumAsDebugProjection() {
    // 意图：catalog 与 Debug 共用同一个枚举，客户端只需实现一次三态语义。
    assertEquals(3, EnvironmentSupportDTO.values().length);
    assertEquals("NONE", EnvironmentSupportDTO.NONE.name());
    assertEquals("OPTIONAL", EnvironmentSupportDTO.OPTIONAL.name());
    assertEquals("REQUIRED", EnvironmentSupportDTO.REQUIRED.name());
  }

  @Test
  void agentDefinitionConfigSkillsAreStructuredRefs() throws Exception {
    // 意图：Agent 配置的 skills 是有序 SkillRefDTO 列表，不再是裸 name 字符串。
    Field skills = AgentDefinitionConfigDTO.class.getDeclaredField("skills");
    assertEquals(List.class, skills.getType());
    assertEquals(
        SkillRefDTO.class,
        ((ParameterizedType) skills.getGenericType()).getActualTypeArguments()[0]);

    String configTemplate =
        """
        {"tools":[],"skills":%s,"subagents":[],"inheritParentEnvironment":true}
        """;
    AgentDefinitionConfigDTO dto =
        MAPPER.readValue(
            configTemplate.formatted("[{\"packageName\":\"dev-tools\",\"name\":\"dev\"}]"),
            AgentDefinitionConfigDTO.class);
    assertEquals("dev-tools", dto.getSkills().get(0).getPackageName());
    assertEquals("dev", dto.getSkills().get(0).getName());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                configTemplate.formatted("[\"dev\"]"), AgentDefinitionConfigDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                configTemplate.formatted(
                    "[{\"packageName\":\"dev-tools\",\"name\":\"dev\",\"content\":\"# dev\"}]"),
                AgentDefinitionConfigDTO.class));
  }
}
