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
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.List;

/** Agent 引用解析必须在写入前校验全局 Agent、Model 与 Skill 引用。 */
class AgentDefinitionReferenceResolverTest {

  @Test
  void rejectsMissingAgentAndModelReferences() {
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            mock(SkillCatalogQueryService.class));

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
            definitions, mock(AgentModelRepository.class), mock(SkillCatalogQueryService.class));

    assertSame(
        target,
        resolver.requireAgentAndSubagentsForUpdate("middle", List.of("omega", "alpha", "middle")));

    InOrder ordered = inOrder(definitions);
    ordered.verify(definitions).getByNameForUpdate("alpha");
    ordered.verify(definitions).getByNameForUpdate("middle");
    ordered.verify(definitions).getByNameForUpdate("omega");
  }

  /** 测试意图：Skill 引用校验：查询 package 及其 manifest，任一 Package 或 Skill 缺失都 fail closed。 */
  @Test
  void validatesSkillReferencesAndRejectsMissingOnes() {
    SkillCatalogQueryService skillQueryService = mock(SkillCatalogQueryService.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            skillQueryService);

    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("tools");
    pkg.setSkills(
        List.of(
            new SkillManifestEntry("dev", "dev desc"), new SkillManifestEntry("ops", "ops desc")));
    when(skillQueryService.getPackage("tools")).thenReturn(pkg);

    assertDoesNotThrow(
        () ->
            resolver.requireCurrentSkills(
                List.of(skillRef("tools", "ops"), skillRef("tools", "dev"))));

    // 缺少 skill
    assertThrows(
        AiValidationException.class,
        () ->
            resolver.requireCurrentSkills(
                List.of(skillRef("tools", "missing"), skillRef("tools", "dev"))));

    // 缺少 package
    assertThrows(
        AiValidationException.class,
        () -> resolver.requireCurrentSkills(List.of(skillRef("missing-pkg", "dev"))));
  }

  @Test
  void emptySkillSelectionDoesNotQueryCatalog() {
    SkillCatalogQueryService skillQueryService = mock(SkillCatalogQueryService.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            skillQueryService);

    assertDoesNotThrow(() -> resolver.requireCurrentSkills(List.of()));
    verify(skillQueryService, never()).getPackage(null);
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }
}
