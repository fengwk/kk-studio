package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSourceService;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;

import java.util.List;
import java.util.UUID;

/** 验证 {@link EnvironmentOperationService} 的语义校验、全局锁调用、冻结配置及取消边界契约。 */
class EnvironmentOperationServiceTest extends PostgresSpringTestSupport {

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private EnvironmentOperationService operationService;
  @Autowired private EnvironmentOperationRepository operationRepository;

  private EnvironmentId environmentId;
  private UUID pathSourceId;
  private UUID gitSourceId;

  @BeforeEach
  void setUp() {
    EnvironmentCreateDTO createEnv = new EnvironmentCreateDTO();
    createEnv.setName("op-svc-env-" + System.nanoTime());
    environmentId = EnvironmentId.of(UUID.fromString(environmentService.create(createEnv).getId()));

    // 默认创建的 PATH 来源
    List<EnvironmentSkillSourceDTO> sources = sourceService.list(environmentId);
    pathSourceId = UUID.fromString(sources.getFirst().getSourceId());

    // 创建一个未安装的 GIT 来源
    EnvironmentSkillSourceCreateDTO createGit = new EnvironmentSkillSourceCreateDTO();
    createGit.setType("git");
    createGit.setGitUrl("https://github.com/example/skills.git");
    EnvironmentSkillSourceDTO gitSource = sourceService.create(environmentId, createGit);
    gitSourceId = UUID.fromString(gitSource.getSourceId());
  }

  /** 测试意图：验证入参中超时时间校验：空值、非正数以及超出能力描述符上限均抛出 AiValidationException。 */
  @Test
  void createValidatesTimeoutConstraints() {
    EnvironmentOperationCreateDTO nullDto = new EnvironmentOperationCreateDTO();
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.create(
                environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, nullDto));

    EnvironmentOperationCreateDTO nonPositiveDto = new EnvironmentOperationCreateDTO();
    nonPositiveDto.setTimeoutMillis(0L);
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.create(
                environmentId,
                pathSourceId,
                EnvironmentOperationType.SKILL_REFRESH,
                nonPositiveDto));

    // REFRESH 能力上限为 5 分钟 (300,000 ms)
    EnvironmentOperationCreateDTO exceedDto = new EnvironmentOperationCreateDTO();
    exceedDto.setTimeoutMillis(300001L);
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.create(
                environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, exceedDto));
  }

  /** 测试意图：验证来源语义约束：PATH 来源不支持 INSTALL/UPDATE，GIT 来源未安装前不支持 REFRESH。 */
  @Test
  void createValidatesSourceSemantics() {
    EnvironmentOperationCreateDTO validDto = new EnvironmentOperationCreateDTO();
    validDto.setTimeoutMillis(60000L);

    // PATH 来源不允许 INSTALL
    AiValidationException ex1 =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.create(
                    environmentId, pathSourceId, EnvironmentOperationType.SKILL_INSTALL, validDto));
    assertTrue(ex1.getMessage().contains("only valid for GIT sources"));

    // PATH 来源不允许 UPDATE
    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.create(
                    environmentId, pathSourceId, EnvironmentOperationType.SKILL_UPDATE, validDto));
    assertTrue(ex2.getMessage().contains("only valid for GIT sources"));

    // GIT 来源未安装（无 appliedRevision）不允许 REFRESH
    AiValidationException ex3 =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.create(
                    environmentId, gitSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto));
    assertTrue(ex3.getMessage().contains("install is required before refresh"));
  }

  /** 测试意图：验证合法创建操作成功生成处于 PENDING 状态的安全 DTO，并在同一来源已有活跃操作时拒绝重复并发。 */
  @Test
  void createPendingOperationSuccessfullyAndPreventsDuplicateActive() {
    EnvironmentOperationCreateDTO validDto = new EnvironmentOperationCreateDTO();
    validDto.setTimeoutMillis(60000L);

    EnvironmentOperationDTO dto =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);

    assertNotNull(dto);
    assertNotNull(dto.getId());
    assertEquals(environmentId.value().toString(), dto.getEnvironmentId());
    assertEquals(pathSourceId.toString(), dto.getSourceId());
    assertEquals("SKILL_REFRESH", dto.getOperationType());
    assertEquals("PENDING", dto.getStatus());
    assertNotNull(dto.getDeadlineAt());
    assertNotNull(dto.getParameterSummary());

    // 同一来源重复创建活跃操作抛出 409
    assertThrows(
        AiDuplicateException.class,
        () ->
            operationService.create(
                environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto));
  }

  /** 测试意图：验证 get 查询操作安全投影及环境隔离（错配环境返回 404）。 */
  @Test
  void getReturnsSafeDtoAndEnforcesEnvironmentIsolation() {
    EnvironmentOperationCreateDTO validDto = new EnvironmentOperationCreateDTO();
    validDto.setTimeoutMillis(60000L);

    EnvironmentOperationDTO created =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);

    EnvironmentOperationDTO fetched =
        operationService.get(environmentId, UUID.fromString(created.getId()));
    assertEquals(created.getId(), fetched.getId());

    EnvironmentId otherEnvId = EnvironmentId.of(UUID.randomUUID());
    assertThrows(
        AiResourceNotFoundException.class,
        () -> operationService.get(otherEnvId, UUID.fromString(created.getId())));
  }

  /** 测试意图：验证 cancel 仅允许取消 PENDING 操作；非 PENDING 或错配环境均抛出异常。 */
  @Test
  void cancelRestrictedToPendingOperations() {
    EnvironmentOperationCreateDTO validDto = new EnvironmentOperationCreateDTO();
    validDto.setTimeoutMillis(60000L);

    EnvironmentOperationDTO created =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);
    UUID opId = UUID.fromString(created.getId());

    // 环境错配返回 404
    EnvironmentId otherEnvId = EnvironmentId.of(UUID.randomUUID());
    assertThrows(
        AiResourceNotFoundException.class, () -> operationService.cancel(otherEnvId, opId));

    // 成功取消 PENDING 操作
    EnvironmentOperationDTO cancelled = operationService.cancel(environmentId, opId);
    assertEquals("CANCELLED", cancelled.getStatus());

    // 已经处于 CANCELLED 状态再次取消抛出 AiValidationException
    assertThrows(AiValidationException.class, () -> operationService.cancel(environmentId, opId));
  }
}
