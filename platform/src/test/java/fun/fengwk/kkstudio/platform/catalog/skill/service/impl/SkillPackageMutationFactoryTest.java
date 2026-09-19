package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.skill.SkillDefinitionDTO;

import java.util.Arrays;
import java.util.List;

/** Skill package 请求规范化与服务端 revision 计算测试。 */
class SkillPackageMutationFactoryTest {

  private final SkillPackageMutationFactory factory =
      new SkillPackageMutationFactory(new AgentEditableSupport(new ObjectMapper()));

  /** 意图：规范化结果保留正文精确字节，并为 package 与每个 Skill 计算确定性 SHA-256。 */
  @Test
  void normalizesPackageAndComputesRevisions() {
    SkillPackageMutationFactory.Mutation first =
        factory.newMutation(
            "core",
            "1",
            "  ",
            List.of(skill("dev", "Development", "hello"), skill("ops", "Operations", "  ")));
    SkillPackageMutationFactory.Mutation second =
        factory.newMutation(
            "core",
            "1",
            null,
            List.of(skill("dev", "Development", "hello"), skill("ops", "Operations", "  ")));

    assertNull(first.skillPackage().getDescription());
    assertEquals(
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        first.revisions().get(0).getContentRevision());
    assertEquals("  ", first.revisions().get(1).getContent());
    assertEquals(
        first.skillPackage().getPackageRevision(), second.skillPackage().getPackageRevision());
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

  private static SkillDefinitionDTO skill(String name, String description, String content) {
    SkillDefinitionDTO dto = new SkillDefinitionDTO();
    dto.setName(name);
    dto.setDescription(description);
    dto.setContent(content);
    return dto;
  }
}
