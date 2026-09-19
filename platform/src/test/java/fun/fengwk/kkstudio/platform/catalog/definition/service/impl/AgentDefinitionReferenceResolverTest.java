package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.CurrentSkill;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.List;
import java.util.Set;

/** Agent 引用解析必须在写入前锁定全局 Agent、Model 与 Skill。 */
class AgentDefinitionReferenceResolverTest {

  @Test
  void rejectsMissingAgentAndModelReferences() {
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            mock(SkillCatalogRepository.class));

    assertThrows(AiResourceNotFoundException.class, () -> resolver.requireAgent("missing"));
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireModel("provider", "missing"));
  }

  @Test
  void locksUpdatedAgentAndSubagentsInCanonicalOrder() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentDefinition target = new AgentDefinition();
    AgentDefinition alpha = new AgentDefinition();
    AgentDefinition omega = new AgentDefinition();
    when(definitions.getByNameForUpdate("alpha")).thenReturn(alpha);
    when(definitions.getByNameForUpdate("middle")).thenReturn(target);
    when(definitions.getByNameForUpdate("omega")).thenReturn(omega);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            definitions, mock(AgentModelRepository.class), mock(SkillCatalogRepository.class));

    assertSame(
        target,
        resolver.requireAgentAndSubagentsForUpdate("middle", List.of("omega", "alpha", "middle")));

    InOrder ordered = inOrder(definitions);
    ordered.verify(definitions).getByNameForUpdate("alpha");
    ordered.verify(definitions).getByNameForUpdate("middle");
    ordered.verify(definitions).getByNameForUpdate("omega");
  }

  /** Skill 统一按名称排序后一次加 KEY SHARE 锁，缺失任一名称都 fail closed。 */
  @Test
  void locksSkillsInCanonicalOrderAndRejectsMissingNames() {
    SkillCatalogRepository skills = mock(SkillCatalogRepository.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class), mock(AgentModelRepository.class), skills);

    when(skills.lockCurrentSkillsByNames(Set.of("dev", "ops")))
        .thenReturn(List.of(entry("dev"), entry("ops")));
    assertDoesNotThrow(() -> resolver.requireCurrentSkills(List.of("ops", "dev")));
    verify(skills).lockCurrentSkillsByNames(Set.of("dev", "ops"));

    when(skills.lockCurrentSkillsByNames(Set.of("dev", "missing")))
        .thenReturn(List.of(entry("dev")));
    assertThrows(
        AiValidationException.class,
        () -> resolver.requireCurrentSkills(List.of("missing", "dev")));
  }

  @Test
  void emptySkillSelectionDoesNotAcquireLocks() {
    SkillCatalogRepository skills = mock(SkillCatalogRepository.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class), mock(AgentModelRepository.class), skills);

    assertDoesNotThrow(() -> resolver.requireCurrentSkills(List.of()));
    verify(skills, never()).lockCurrentSkillsByNames(List.of());
  }

  private static CurrentSkill entry(String name) {
    CurrentSkill skill = new CurrentSkill();
    skill.setName(name);
    skill.setPackageName("package");
    skill.setPackageVersion("1.0.0");
    return skill;
  }
}
