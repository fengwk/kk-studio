package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSourceService;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
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
import java.util.Set;
import java.util.UUID;

/** 验证 {@link EnvironmentOperationService} 的语义校验、全局锁调用、冻结配置及取消与历史列表契约。 */
class EnvironmentOperationServiceTest extends PostgresSpringTestSupport {

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private SkillSourceRepository skillSourceRepository;
  @Autowired private EnvironmentOperationService operationService;
  @Autowired private EnvironmentOperationRepository operationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final DaemonSkillSourceConfigCodec configCodec = new DaemonSkillSourceConfigCodec();

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

  /**
   * 测试意图：验证冻结参数解码：PATH 刷新始终传递 null；完全匹配的 GIT 传递 revision；修改陈旧的 GIT 刷新被拒且 update 冻结 null；活跃 ID
   * 来自全部加锁来源。
   */
  @Test
  void frozenArgumentsDecodeExactRevisionsAndAllActiveSourceIds() {
    EnvironmentOperationCreateDTO validDto = new EnvironmentOperationCreateDTO();
    validDto.setTimeoutMillis(60000L);

    // 1. PATH 来源即便已有 appliedRevision，REFRESH 也必须传递 null
    String pathRev = "a".repeat(64);
    skillSourceRepository.markSourceReady(
        environmentId.value(), pathSourceId, 0L, pathRev, List.of());
    EnvironmentOperationDTO pathOpDto =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);
    EnvironmentOperation pathOp = operationRepository.getById(UUID.fromString(pathOpDto.getId()));
    DaemonSkillSourceConfig pathConfig = configCodec.decode(pathOp.arguments());
    assertNull(pathConfig.currentlyAppliedRevision(), "PATH refresh 必须冻结 null 目前版本");
    assertEquals(Set.of(pathSourceId, gitSourceId), pathConfig.activeSourceIds());

    // 取消 PATH 操作避免并发冲突
    operationService.cancel(environmentId, UUID.fromString(pathOpDto.getId()));

    // 2. GIT 来源在 appliedVersion == version 时 REFRESH 必须携带 revision
    String gitRev = "b".repeat(40);
    skillSourceRepository.markSourceReady(
        environmentId.value(), gitSourceId, 0L, gitRev, List.of());
    EnvironmentOperationDTO gitRefreshDto =
        operationService.create(
            environmentId, gitSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);
    EnvironmentOperation gitOp =
        operationRepository.getById(UUID.fromString(gitRefreshDto.getId()));
    DaemonSkillSourceConfig gitRefreshConfig = configCodec.decode(gitOp.arguments());
    assertEquals(gitRev, gitRefreshConfig.currentlyAppliedRevision());
    assertEquals(Set.of(pathSourceId, gitSourceId), gitRefreshConfig.activeSourceIds());

    operationService.cancel(environmentId, UUID.fromString(gitRefreshDto.getId()));

    // 3. 修改 GIT 来源配置使 version = 1，而 appliedVersion 保持为 0（陈旧）
    SkillSource gitSource = skillSourceRepository.getSource(environmentId.value(), gitSourceId);
    gitSource.setGitRef("feature/new-branch");
    skillSourceRepository.updateSourceByVersion(gitSource, 0L);

    // 陈旧版本 GIT REFRESH 必须被拒绝
    AiValidationException staleEx =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.create(
                    environmentId, gitSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto));
    assertTrue(staleEx.getMessage().contains("install is required before refresh"));

    // 但是 GIT UPDATE 允许执行，且冻结的 currentlyAppliedRevision 必须为 null
    EnvironmentOperationDTO gitUpdateDto =
        operationService.create(
            environmentId, gitSourceId, EnvironmentOperationType.SKILL_UPDATE, validDto);
    EnvironmentOperation gitUpdateOp =
        operationRepository.getById(UUID.fromString(gitUpdateDto.getId()));
    DaemonSkillSourceConfig gitUpdateConfig = configCodec.decode(gitUpdateOp.arguments());
    assertNull(gitUpdateConfig.currentlyAppliedRevision(), "配置已修改的 GIT UPDATE 必须冻结 null");
  }

  /** 测试意图：验证 list 历史列表：非正 limit 抛出校验异常，不存在环境抛出 404，正常返回倒序安全投影列表并受 limit 约束。 */
  @Test
  void listEnforcesValidationAndBoundsHistory() {
    EnvironmentOperationCreateDTO validDto = new EnvironmentOperationCreateDTO();
    validDto.setTimeoutMillis(60000L);

    // limit 非正数抛出异常
    assertThrows(AiValidationException.class, () -> operationService.list(environmentId, 0));
    assertThrows(AiValidationException.class, () -> operationService.list(environmentId, -1));

    // 环境不存在抛出 404
    assertThrows(
        AiResourceNotFoundException.class,
        () -> operationService.list(EnvironmentId.of(UUID.randomUUID()), 10));

    // 创建并取消一个操作
    EnvironmentOperationDTO op1 =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);
    operationService.cancel(environmentId, UUID.fromString(op1.getId()));

    // 再创建一个操作
    EnvironmentOperationDTO op2 =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, validDto);

    List<EnvironmentOperationDTO> list = operationService.list(environmentId, 10);
    assertEquals(2, list.size());
    // 验证倒序
    assertEquals(op2.getId(), list.get(0).getId());
    assertEquals(op1.getId(), list.get(1).getId());

    // 验证 limit 约束
    List<EnvironmentOperationDTO> boundedList = operationService.list(environmentId, 1);
    assertEquals(1, boundedList.size());
    assertEquals(op2.getId(), boundedList.getFirst().getId());
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
    assertEquals("SKILL_SOURCE", dto.getResourceType());
    assertEquals(pathSourceId.toString(), dto.getResourceId());
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

  /**
   * 测试意图：验证通用 createOperation 在同一套参数校验与能力描述符约束下成功创建 PENDING 操作，并复现边界：负 resourceVersion、非正
   * timeoutMillis、超描述符上限 timeoutMillis 与环境不存在时均拒绝创建。
   */
  @Test
  void createGenericOperationSuccessAndBoundaries() {
    EnvironmentOperationDTO dto =
        operationService.createOperation(
            environmentId,
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationResourceType.SKILL_SOURCE,
            pathSourceId,
            3L,
            "{}",
            "{\"type\":\"PATH\"}",
            60000L);

    assertEquals("SKILL_REFRESH", dto.getOperationType());
    assertEquals("SKILL_SOURCE", dto.getResourceType());
    assertEquals(pathSourceId.toString(), dto.getResourceId());
    assertEquals("3", dto.getResourceVersion());
    assertEquals("PENDING", dto.getStatus());
    assertEquals("PATH", dto.getParameterSummary().get("type"));

    // 负 resourceVersion 被拒绝
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.createOperation(
                environmentId,
                EnvironmentOperationType.SKILL_REFRESH,
                EnvironmentOperationResourceType.SKILL_SOURCE,
                UUID.randomUUID(),
                -1L,
                "{}",
                "{}",
                60000L));

    // 非正 timeoutMillis 被拒绝
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.createOperation(
                environmentId,
                EnvironmentOperationType.SKILL_REFRESH,
                EnvironmentOperationResourceType.SKILL_SOURCE,
                UUID.randomUUID(),
                0L,
                "{}",
                "{}",
                0L));

    // 超过 SKILL_SOURCE_REFRESH 描述符上限（5 分钟）的 timeoutMillis 被拒绝
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.createOperation(
                environmentId,
                EnvironmentOperationType.SKILL_REFRESH,
                EnvironmentOperationResourceType.SKILL_SOURCE,
                UUID.randomUUID(),
                0L,
                "{}",
                "{}",
                600001L));

    // 环境不存在时拒绝创建
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            operationService.createOperation(
                EnvironmentId.of(UUID.randomUUID()),
                EnvironmentOperationType.SKILL_REFRESH,
                EnvironmentOperationResourceType.SKILL_SOURCE,
                UUID.randomUUID(),
                0L,
                "{}",
                "{}",
                60000L));
  }

  /** 测试意图：验证已注册的 MCP discovery 管理能力可通过通用入口创建并持久化资源身份。 */
  @Test
  void createGenericMcpDiscoveryOperation() {
    UUID mcpResourceId = UUID.randomUUID();
    String arguments =
        """
        {
          "serverId": "%s",
          "configVersion": 0,
          "config": {
            "type": "local",
            "environmentId": "%s",
            "command": ["node", "server.js"],
            "cwd": "/tmp"
          }
        }
        """
            .formatted(mcpResourceId, environmentId);

    EnvironmentOperationDTO created =
        operationService.createOperation(
            environmentId,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            mcpResourceId,
            0L,
            arguments,
            "{\"type\":\"local\"}",
            60000L);

    assertEquals("MCP_SERVER", created.getResourceType());
    assertEquals(mcpResourceId.toString(), created.getResourceId());
    assertEquals("MCP_SERVER_DISCOVER", created.getOperationType());
    assertEquals("PENDING", created.getStatus());
  }

  /** 测试意图：验证通用 createOperation 拒绝 operationType 与 resourceType 不匹配的组合。 */
  @Test
  void createGenericOperationRejectsResourceTypeMismatch() {
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.createOperation(
                    environmentId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    EnvironmentOperationResourceType.MCP_SERVER,
                    UUID.randomUUID(),
                    0L,
                    "{}",
                    "{}",
                    60000L));
    assertTrue(ex.getMessage().contains("is incompatible with resourceType"));
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

  /** 测试意图：验证语义校验拒绝针对 PATH 来源发起 SKILL_UPDATE 操作。 */
  @Test
  void validateOperationSemantics_rejectsUpdateOnPathSource() {
    EnvironmentOperationCreateDTO dto = new EnvironmentOperationCreateDTO();
    dto.setTimeoutMillis(60000L);
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.create(
                    environmentId, pathSourceId, EnvironmentOperationType.SKILL_UPDATE, dto));
    assertTrue(ex.getMessage().contains("operation UPDATE is only valid for GIT sources"));
  }

  /** 测试意图：验证语义校验拒绝针对未安装/无有效应用版本的 GIT 来源发起 SKILL_REFRESH 操作。 */
  @Test
  void validateOperationSemantics_rejectsRefreshOnUninstalledGitSource() {
    EnvironmentOperationCreateDTO dto = new EnvironmentOperationCreateDTO();
    dto.setTimeoutMillis(60000L);
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                operationService.create(
                    environmentId, gitSourceId, EnvironmentOperationType.SKILL_REFRESH, dto));
    assertTrue(ex.getMessage().contains("install is required before refresh"));
  }

  /** 测试意图：验证 cancel 拒绝取消已经处于 RUNNING 状态的操作。 */
  @Test
  void cancel_whenRunning_throwsAiValidationException() {
    EnvironmentOperationCreateDTO dto = new EnvironmentOperationCreateDTO();
    dto.setTimeoutMillis(60000L);

    EnvironmentOperationDTO created =
        operationService.create(
            environmentId, pathSourceId, EnvironmentOperationType.SKILL_REFRESH, dto);
    UUID opId = UUID.fromString(created.getId());

    // 模拟被调度节点认领变为 RUNNING
    jdbcTemplate.update(
        "UPDATE environment_operation SET status = 'RUNNING', owner_node_id = ?, lease_token = ?, started_at = now() WHERE id = ?",
        UUID.randomUUID(),
        UUID.randomUUID(),
        opId);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> operationService.cancel(environmentId, opId));
    assertTrue(
        ex.getMessage().contains("operation cannot be cancelled because it is in status RUNNING"));
  }
}
