package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.DomainErrorCode;

import java.util.List;
import java.util.UUID;

class EnvironmentSkillSourceServiceImplTest {

  private static final EnvironmentId ENV_ID =
      EnvironmentId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final UUID SOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  /** 测试意图：验证删除 SkillSource 时，若存在 AgentDefinition 引用该 sourceId，服务必须抛出 AiInUseException 并在底层拒绝删除。 */
  @Test
  void deleteRejectsWhenSkillSourceIsReferencedByAgentDefinition() {
    EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSourceRepository = mock(SkillSourceRepository.class);
    AgentDefinitionRepository agentDefinitionRepository = mock(AgentDefinitionRepository.class);

    EnvironmentSkillSourceServiceImpl service =
        new EnvironmentSkillSourceServiceImpl(
            environmentRepository, skillSourceRepository, agentDefinitionRepository);

    when(environmentRepository.lockForKeyShare(ENV_ID.value())).thenReturn(new Environment());
    EnvironmentInventory inventory = new EnvironmentInventory();
    inventory.setSourceSetVersion(0L);
    when(skillSourceRepository.lockInventory(ENV_ID.value())).thenReturn(inventory);

    SkillSource source = new SkillSource();
    source.setSourceId(SOURCE_ID);
    source.setEnvironmentId(ENV_ID.value());
    source.setType(DaemonSkillSourceType.PATH);
    source.setPath("/path");
    source.setStatus(SkillSourceStatus.READY);
    source.setVersion(0L);
    when(skillSourceRepository.lockAllSources(ENV_ID.value())).thenReturn(List.of(source));

    // AgentDefinition 引用此 sourceId
    when(agentDefinitionRepository.existsReferencingSkillSource(ENV_ID.value(), SOURCE_ID))
        .thenReturn(true);

    AiInUseException ex =
        assertThrows(AiInUseException.class, () -> service.delete(ENV_ID, SOURCE_ID, "0"));

    assertEquals(DomainErrorCode.IN_USE, ex.code());
    assertTrue(ex.getMessage().contains("referenced by an agent"));
    verify(skillSourceRepository, never()).deleteSourceByVersion(ENV_ID.value(), SOURCE_ID, 0L);
  }

  /** 测试意图：验证当没有任何 AgentDefinition 引用目标 SkillSource 时，删除操作正常执行，推进集合版本并调用持久层删除。 */
  @Test
  void deleteSucceedsWhenNotReferencedByAnyAgentDefinition() {
    EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSourceRepository = mock(SkillSourceRepository.class);
    AgentDefinitionRepository agentDefinitionRepository = mock(AgentDefinitionRepository.class);

    EnvironmentSkillSourceServiceImpl service =
        new EnvironmentSkillSourceServiceImpl(
            environmentRepository, skillSourceRepository, agentDefinitionRepository);

    when(environmentRepository.lockForKeyShare(ENV_ID.value())).thenReturn(new Environment());
    EnvironmentInventory inventory = new EnvironmentInventory();
    inventory.setSourceSetVersion(0L);
    when(skillSourceRepository.lockInventory(ENV_ID.value())).thenReturn(inventory);

    SkillSource source = new SkillSource();
    source.setSourceId(SOURCE_ID);
    source.setEnvironmentId(ENV_ID.value());
    source.setType(DaemonSkillSourceType.PATH);
    source.setPath("/path");
    source.setStatus(SkillSourceStatus.READY);
    source.setVersion(0L);
    when(skillSourceRepository.lockAllSources(ENV_ID.value())).thenReturn(List.of(source));

    // 无任何 AgentDefinition 引用此 sourceId
    when(agentDefinitionRepository.existsReferencingSkillSource(ENV_ID.value(), SOURCE_ID))
        .thenReturn(false);
    when(skillSourceRepository.deleteSourceByVersion(ENV_ID.value(), SOURCE_ID, 0L))
        .thenReturn(true);
    when(skillSourceRepository.incrementSourceSetVersion(ENV_ID.value())).thenReturn(true);

    assertDoesNotThrow(() -> service.delete(ENV_ID, SOURCE_ID, "0"));
    verify(skillSourceRepository).deleteSourceByVersion(ENV_ID.value(), SOURCE_ID, 0L);
    verify(skillSourceRepository).incrementSourceSetVersion(ENV_ID.value());
  }

  /** 测试意图：验证创建和更新技能源时对空请求体进行校验，抛出 AiValidationException。 */
  @Test
  void nullRequestsFailValidation() {
    EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSourceRepository = mock(SkillSourceRepository.class);
    AgentDefinitionRepository agentDefinitionRepository = mock(AgentDefinitionRepository.class);

    EnvironmentSkillSourceServiceImpl service =
        new EnvironmentSkillSourceServiceImpl(
            environmentRepository, skillSourceRepository, agentDefinitionRepository);

    assertThrows(AiValidationException.class, () -> service.create(ENV_ID, null));
    assertThrows(AiValidationException.class, () -> service.update(ENV_ID, SOURCE_ID, null));
  }

  /** 测试意图：验证查询不存在的技能源时抛出 AiResourceNotFoundException。 */
  @Test
  void getMissingSourceThrowsNotFound() {
    EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
    SkillSourceRepository skillSourceRepository = mock(SkillSourceRepository.class);
    AgentDefinitionRepository agentDefinitionRepository = mock(AgentDefinitionRepository.class);

    EnvironmentSkillSourceServiceImpl service =
        new EnvironmentSkillSourceServiceImpl(
            environmentRepository, skillSourceRepository, agentDefinitionRepository);

    when(skillSourceRepository.getSource(ENV_ID.value(), SOURCE_ID)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.get(ENV_ID, SOURCE_ID));
  }
}
