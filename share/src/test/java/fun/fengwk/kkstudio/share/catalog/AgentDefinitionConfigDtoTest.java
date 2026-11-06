package fun.fengwk.kkstudio.share.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.skill.SkillRefDTO;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;

/** Agent 配置的 wire 契约：skills 是有序结构化引用。 */
class AgentDefinitionConfigDtoTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void agentDefinitionConfigSkillsAreStructuredRefs() throws Exception {
    // 意图：Agent 配置的 skills 是有序 SkillRefDTO 列表，未知字段与非结构化引用被拒绝。
    Field skills = AgentDefinitionConfigDTO.class.getDeclaredField("skills");
    assertEquals(List.class, skills.getType());
    assertEquals(
        SkillRefDTO.class,
        ((ParameterizedType) skills.getGenericType()).getActualTypeArguments()[0]);

    String configTemplate = """
        {"tools":[],"skills":%s,"subagents":[]}
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
                    "[{\"packageName\":\"dev-tools\",\"name\":\"dev\",\"unknownField\":\"value\"}]"),
                AgentDefinitionConfigDTO.class));
  }
}
