package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;

import java.util.List;
import java.util.UUID;

/** 缺失的全局 Agent / model / environment 引用在持久化变更前必须失败。 */
public class AgentDefinitionReferenceResolverTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SOURCE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  @Test
  public void shouldRejectMissingReferences() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentModelRepository models = mock(AgentModelRepository.class);
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(definitions, models, environments, skillSources);

    assertThrows(AiResourceNotFoundException.class, () -> resolver.requireAgent("missing"));
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireModel("provider", "missing"));
    UUID envId = UUID.randomUUID();
    when(environments.lockForKeyShare(envId)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireEnvironmentForShare(envId));

    assertDoesNotThrow(() -> resolver.requireEnvironmentForShare(null));
    when(environments.lockForKeyShare(envId)).thenReturn(new Environment());
    assertDoesNotThrow(() -> resolver.requireEnvironmentForShare(envId));
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
            definitions,
            mock(AgentModelRepository.class),
            mock(EnvironmentRepository.class),
            mock(SkillSourceRepository.class));

    assertSame(
        target,
        resolver.requireAgentAndSubagentsForUpdate("middle", List.of("omega", "alpha", "middle")));

    InOrder ordered = inOrder(definitions);
    ordered.verify(definitions).getByNameForUpdate("alpha");
    ordered.verify(definitions).getByNameForUpdate("middle");
    ordered.verify(definitions).getByNameForUpdate("omega");
  }

  /** 测试意图：验证 Agent 未绑定 Environment 时，如果配置了非空 skills 引用，必须抛出 AiValidationException 拒绝保存。 */
  @Test
  void requireEnvironmentAndSkillsRejectsNonEmptySkillsWhenEnvironmentIsNull() {
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            mock(EnvironmentRepository.class),
            mock(SkillSourceRepository.class));

    List<AgentSkillRefDTO> skills = List.of(new AgentSkillRefDTO(SOURCE_A.toString(), "dev"));
    AiValidationException error =
        assertThrows(
            AiValidationException.class, () -> resolver.requireEnvironmentAndSkills(null, skills));
    assertTrue(error.getMessage().contains("skills require an environment"));
  }

  /** 测试意图：验证配置非空 skills 引用时，如果指定的 Environment 不存在，抛出 AiResourceNotFoundException。 */
  @Test
  void requireEnvironmentAndSkillsRejectsWhenEnvironmentMissing() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    when(environments.lockForKeyShare(ENV_ID)).thenReturn(null);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            mock(SkillSourceRepository.class));

    List<AgentSkillRefDTO> skills = List.of(new AgentSkillRefDTO(SOURCE_A.toString(), "dev"));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> resolver.requireEnvironmentAndSkills(ENV_ID, skills));
  }

  /** 测试意图：验证配置非空 skills 引用时，如果 Environment 的 inventory 头不存在，抛出 AiResourceNotFoundException。 */
  @Test
  void requireEnvironmentAndSkillsRejectsWhenInventoryMissing() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(environments.lockForKeyShare(ENV_ID)).thenReturn(new Environment());
    when(skillSources.lockInventory(ENV_ID)).thenReturn(null);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            skillSources);

    List<AgentSkillRefDTO> skills = List.of(new AgentSkillRefDTO(SOURCE_A.toString(), "dev"));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> resolver.requireEnvironmentAndSkills(ENV_ID, skills));
  }

  /**
   * 测试意图：验证配置非空 skills 时，按严格顺序加锁（environment KEY SHARE -> inventory FOR UPDATE -> all sources FOR
   * UPDATE）并读取 usableSkills。
   */
  @Test
  void requireEnvironmentAndSkillsLocksInStrictOrder() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(environments.lockForKeyShare(ENV_ID)).thenReturn(new Environment());
    when(skillSources.lockInventory(ENV_ID)).thenReturn(new EnvironmentInventory());

    SkillInventoryEntry usableEntry = new SkillInventoryEntry();
    usableEntry.setSourceId(SOURCE_A);
    usableEntry.setName("dev");
    when(skillSources.listUsableSkills(ENV_ID)).thenReturn(List.of(usableEntry));

    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            skillSources);

    List<AgentSkillRefDTO> skills = List.of(new AgentSkillRefDTO(SOURCE_A.toString(), "dev"));
    assertDoesNotThrow(() -> resolver.requireEnvironmentAndSkills(ENV_ID, skills));

    InOrder ordered = inOrder(environments, skillSources);
    ordered.verify(environments).lockForKeyShare(ENV_ID);
    ordered.verify(skillSources).lockInventory(ENV_ID);
    ordered.verify(skillSources).lockAllSources(ENV_ID);
    ordered.verify(skillSources).listUsableSkills(ENV_ID);
  }

  /** 测试意图：验证当引用的 Skill 不存在于当前 usableSkills 中时，抛出 AiValidationException 拒绝。 */
  @Test
  void requireEnvironmentAndSkillsRejectsUnusableSkillRef() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(environments.lockForKeyShare(ENV_ID)).thenReturn(new Environment());
    when(skillSources.lockInventory(ENV_ID)).thenReturn(new EnvironmentInventory());
    when(skillSources.listUsableSkills(ENV_ID)).thenReturn(List.of());

    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            skillSources);

    List<AgentSkillRefDTO> skills = List.of(new AgentSkillRefDTO(SOURCE_A.toString(), "dev"));
    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () -> resolver.requireEnvironmentAndSkills(ENV_ID, skills));
    assertTrue(error.getMessage().contains("skill ref is not usable in environment"));
  }

  /** 测试意图：验证即使存在同名技能，但如果来源 sourceId 不匹配，必须抛出 AiValidationException 拒绝引用，绝不跨 sourceId 降级匹配。 */
  @Test
  void requireEnvironmentAndSkillsRejectsSameNameFromDifferentSourceId() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(environments.lockForKeyShare(ENV_ID)).thenReturn(new Environment());
    when(skillSources.lockInventory(ENV_ID)).thenReturn(new EnvironmentInventory());

    UUID sourceB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    SkillInventoryEntry usableEntry = new SkillInventoryEntry();
    usableEntry.setSourceId(sourceB);
    usableEntry.setName("dev");
    when(skillSources.listUsableSkills(ENV_ID)).thenReturn(List.of(usableEntry));

    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            skillSources);

    List<AgentSkillRefDTO> skills = List.of(new AgentSkillRefDTO(SOURCE_A.toString(), "dev"));
    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () -> resolver.requireEnvironmentAndSkills(ENV_ID, skills));
    assertTrue(error.getMessage().contains("skill ref is not usable in environment"));
  }

  /** 测试意图：验证 skills 为空时，仅加锁 Environment KEY SHARE，不获取 inventory 与 sources 锁，避免不必要的锁竞争。 */
  @Test
  void requireEnvironmentAndSkillsWithEmptySkillsLocksEnvironmentOnly() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(environments.lockForKeyShare(ENV_ID)).thenReturn(new Environment());

    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            skillSources);

    assertDoesNotThrow(() -> resolver.requireEnvironmentAndSkills(ENV_ID, List.of()));
    verify(environments).lockForKeyShare(ENV_ID);
    verify(skillSources, never()).lockInventory(ENV_ID);
    verify(skillSources, never()).lockAllSources(ENV_ID);
  }

  /** 测试意图：验证 skills 为空且未配置 environmentId 时，不执行任何加锁操作。 */
  @Test
  void requireEnvironmentAndSkillsWithEmptySkillsAndNullEnvironmentDoesNothing() {
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);

    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            environments,
            skillSources);

    assertDoesNotThrow(() -> resolver.requireEnvironmentAndSkills(null, List.of()));
    verify(environments, never()).lockForKeyShare(null);
    verify(skillSources, never()).lockInventory(null);
  }
}
