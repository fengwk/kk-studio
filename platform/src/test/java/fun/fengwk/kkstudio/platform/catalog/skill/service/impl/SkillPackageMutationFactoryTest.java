package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.skill.SkillDefinitionDTO;

import java.util.Arrays;
import java.util.List;

/** Skill package 请求规范化：canonical 校验、package 内唯一名与正文精确字节。 */
class SkillPackageMutationFactoryTest {

  private final SkillPackageMutationFactory factory =
      new SkillPackageMutationFactory(new AgentEditableSupport(new ObjectMapper()));

  /** 意图：规范化结果保留正文精确字节（含纯空白正文），并把 package 三元组下发给每个 Skill 内容行。 */
  @Test
  void normalizesPackageAndKeepsExactContentBytes() {
    SkillPackageMutationFactory.Mutation mutation =
        factory.newMutation(
            "core",
            "1.0.0",
            "  ",
            List.of(skill("dev", "Development", "hello"), skill("ops", "Operations", "  ")));

    assertNull(mutation.skillPackage().getDescription());
    assertEquals("core", mutation.skillPackage().getPackageName());
    assertEquals("1.0.0", mutation.skillPackage().getPackageVersion());

    List<Skill> skills = mutation.skills();
    assertEquals(List.of("dev", "ops"), skills.stream().map(Skill::getName).toList());
    assertEquals("hello", skills.get(0).getContent());
    // 正文按提交的精确字节保存：不做 trim，也不做任何内容标识计算。
    assertEquals("  ", skills.get(1).getContent());
    for (Skill skill : skills) {
      assertEquals("core", skill.getPackageName());
      assertEquals("1.0.0", skill.getPackageVersion());
    }
  }

  /** 意图：请求边界统一拒绝非法 package 身份、空列表、空元素与 package 内重复名称。 */
  @Test
  void rejectsInvalidPackageShape() {
    assertThrows(
        AiValidationException.class,
        () -> factory.newMutation("bad/name", "1", null, List.of(skill("dev", "D", "x"))));
    assertThrows(
        AiValidationException.class,
        () -> factory.newMutation("core", "\n", null, List.of(skill("dev", "D", "x"))));
    assertThrows(AiValidationException.class, () -> factory.newMutation("core", "1", null, null));
    assertThrows(
        AiValidationException.class, () -> factory.newMutation("core", "1", null, List.of()));
    assertThrows(
        AiValidationException.class,
        () -> factory.newMutation("core", "1", null, Arrays.asList((SkillDefinitionDTO) null)));
    assertThrows(
        AiValidationException.class,
        () ->
            factory.newMutation(
                "core", "1", null, List.of(skill("dev", "D", "x"), skill("dev", "D2", "y"))));
  }

  /** 意图：每个 Skill 的名称、描述和正文都在写库前按同一 canonical 契约校验。 */
  @Test
  void rejectsInvalidSkillFields() {
    assertThrows(
        AiValidationException.class,
        () -> factory.newMutation("core", "1", null, List.of(skill("bad/name", "D", "x"))));
    assertThrows(
        AiValidationException.class,
        () -> factory.newMutation("core", "1", null, List.of(skill("dev", " ", "x"))));
    assertThrows(
        AiValidationException.class,
        () -> factory.newMutation("core", "1", null, List.of(skill("dev", "D", ""))));
  }

  /** 意图：校验失败必须是带 package 资源标签的 typed 领域错误，并指明出错的 Skill。 */
  @Test
  void reportsTypedValidationErrorWithPackageResource() {
    AiValidationException invalidContent =
        assertThrows(
            AiValidationException.class,
            () -> factory.newMutation("core", "1", null, List.of(skill("dev", "D", ""))));

    assertEquals(SkillPackageMutationFactory.RESOURCE, invalidContent.resource());
    assertTrue(invalidContent.getMessage().contains("dev"), invalidContent.getMessage());
    assertTrue(invalidContent.getMessage().contains("content"), invalidContent.getMessage());
  }

  private static SkillDefinitionDTO skill(String name, String description, String content) {
    SkillDefinitionDTO dto = new SkillDefinitionDTO();
    dto.setName(name);
    dto.setDescription(description);
    dto.setContent(content);
    return dto;
  }
}
