package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSourceService;
import fun.fengwk.kkstudio.platform.environment.skill.SkillSourceStatus;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;

import java.util.List;
import java.util.UUID;

/**
 * 验证 {@link EnvironmentOperationResultPublisher} 的数据库集成契约：
 * 包括操作成功的代际推进与主机元数据刷新、严格锁序、围栏失效回滚、代际陈旧拒绝与固定失败码写入。
 */
class EnvironmentOperationResultPublisherIntegrationTest extends PostgresSpringTestSupport {

  private static final String REVISION_A = "0123456789abcdef0123456789abcdef01234567";
  private static final String CONTENT_REV = "0".repeat(64);

  @Autowired private EnvironmentOperationResultPublisher publisher;
  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private SkillSourceRepository skillSourceRepository;
  @Autowired private EnvironmentOperationRepository operationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private EnvironmentId environmentId;
  private UUID defaultSourceId;
  private long defaultSourceSetVersion;
  private UUID ownerNodeId;
  private UUID leaseToken;

  @BeforeEach
  void setUp() {
    ownerNodeId = UUID.randomUUID();
    leaseToken = UUID.randomUUID();

    EnvironmentCreateDTO createDTO = new EnvironmentCreateDTO();
    createDTO.setName("publisher-test-env");
    environmentId =
        new EnvironmentId(UUID.fromString(environmentService.create(createDTO).getId()));

    EnvironmentSkillSourceCreateDTO sourceCreate = new EnvironmentSkillSourceCreateDTO();
    sourceCreate.setType("path");
    sourceCreate.setPath("/skills/demo");
    EnvironmentSkillSourceDTO createdSource = sourceService.create(environmentId, sourceCreate);
    defaultSourceId = UUID.fromString(createdSource.getSourceId());
    defaultSourceSetVersion =
        skillSourceRepository.getInventory(environmentId.value()).getSourceSetVersion();

    DaemonCapabilities capabilities =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "Asia/Shanghai", "test-host-note", "/opt/root"),
            0L,
            List.of());
    String runtimeInfoJson = new DaemonCapabilitiesCodec().encode(capabilities);

    jdbcTemplate.update(
        """
        insert into environment_connection (
            environment_id, owner_node_id, lease_token,
            status, runtime_info, last_seen_at, lease_until
        ) values (
            ?, ?, ?, 'READY',
            ?::jsonb, statement_timestamp(), statement_timestamp() + interval '5 minutes'
        )
        on conflict (environment_id) do update set
            owner_node_id = excluded.owner_node_id,
            lease_token = excluded.lease_token,
            status = 'READY',
            runtime_info = excluded.runtime_info,
            last_seen_at = statement_timestamp(),
            lease_until = excluded.lease_until
        """,
        environmentId.value(),
        ownerNodeId,
        leaseToken,
        runtimeInfoJson);
  }

  /**
   * 测试意图：验证操作成功时将 applied_source_set_version 推进至操作预期的最新 desired 代际（2L）， 而非 runtime_info
   * 中记录的陈旧代际（0L），并使用宿主连接元数据刷新库存。
   */
  @Test
  void publishOperationSuccess_advancesInventoryToDesiredVersionRatherThanStaleRuntimeInfo() {
    EnvironmentSkillSourceCreateDTO secondSourceCreate = new EnvironmentSkillSourceCreateDTO();
    secondSourceCreate.setType("path");
    secondSourceCreate.setPath("/skills/extra");
    EnvironmentSkillSourceDTO secondSource =
        sourceService.create(environmentId, secondSourceCreate);
    UUID secondSourceId = UUID.fromString(secondSource.getSourceId());

    EnvironmentInventory invBefore = skillSourceRepository.getInventory(environmentId.value());
    long desiredVersion = invBefore.getSourceSetVersion();
    assertEquals(2L, desiredVersion, "新增第二个来源后 desired sourceSetVersion 必须为 2");

    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            secondSourceId,
            EnvironmentOperationType.SKILL_INSTALL,
            0L,
            desiredVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);

    List<ClaimedOperation> claimed = operationRepository.claimPendingWithTimeout(ownerNodeId, 10);
    assertEquals(1, claimed.size());
    assertEquals(opId, claimed.getFirst().operation().id());

    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            secondSourceId, 0L, "extra-skill", "Extra skill desc", "/skills/extra", CONTENT_REV);
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(secondSourceId, 0L, REVISION_A, List.of(skill), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(), opId, ownerNodeId, leaseToken, desiredVersion, snapshot);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    EnvironmentInventory inv = skillSourceRepository.getInventory(environmentId.value());
    assertNotNull(inv);
    assertEquals(
        desiredVersion,
        inv.getAppliedSourceSetVersion(),
        "applied_source_set_version 必须已推进至期望的新代际，而非 runtime_info 的陈旧代际 0");
    assertEquals("linux", inv.getOperatingSystem());
    assertEquals("Asia/Shanghai", inv.getTimeZone());
    assertEquals("test-host-note", inv.getNote());
    assertEquals("/opt/root", inv.getRootPath());

    SkillSource src = skillSourceRepository.getSource(environmentId.value(), secondSourceId);
    assertNotNull(src);
    assertEquals(SkillSourceStatus.READY, src.getStatus());
    assertEquals(REVISION_A, src.getAppliedRevision());

    List<SkillInventoryEntry> skills = skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, skills.size());
    assertEquals("extra-skill", skills.getFirst().getName());

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.SUCCEEDED, op.status());
    assertNotNull(op.finishedAt());
    assertTrue(op.resultSummary().contains("\"skillCount\""));
    assertTrue(op.resultSummary().contains("1"));
  }

  /** 测试意图：验证操作终态围栏失败（如已被取消或清扫）时抛出异常并整体回滚，绝不产生半写入的 inventory 或 skill 数据。 */
  @Test
  void publishOperationSuccess_rollsBackAllChangesOnTerminalFenceFailure() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);

    List<ClaimedOperation> claimed = operationRepository.claimPendingWithTimeout(ownerNodeId, 10);
    assertEquals(1, claimed.size());

    // 模拟竞争条件：在 publish 前，该操作被清扫为 UNKNOWN
    operationRepository.markUnknown(opId, ownerNodeId, leaseToken, "TIMEOUT", "Timeout elapsed");

    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "rollback-skill", "Desc", "/skills/rb", CONTENT_REV);
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(skill), List.of());

    assertThrows(
        IllegalStateException.class,
        () ->
            publisher.publishOperationSuccess(
                environmentId.value(),
                opId,
                ownerNodeId,
                leaseToken,
                defaultSourceSetVersion,
                snapshot));

    // 验证事务回滚：Skill 未插入，来源仍未 READY，inventory applied 代际未推进
    List<SkillInventoryEntry> skills = skillSourceRepository.listSkills(environmentId.value());
    assertTrue(skills.isEmpty(), "事务回滚后不得存在持久化 skill 行");

    SkillSource src = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertFalse(src.getStatus() == SkillSourceStatus.READY, "事务回滚后来源不得被标记为 READY");
    assertNull(src.getAppliedRevision());

    EnvironmentInventory inv = skillSourceRepository.getInventory(environmentId.value());
    assertNull(inv.getAppliedSourceSetVersion(), "事务回滚后 applied_source_set_version 不得前进");
  }

  /** 测试意图：验证代际或来源版本变化时原子推进操作至 FAILED (RESOURCE_CHANGED)。 */
  @Test
  void publishOperationSuccess_staleSourceSetVersion_marksResourceChanged() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(), List.of());

    // 期望代际是 999（陈旧）
    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(), opId, ownerNodeId, leaseToken, 999L, snapshot);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证连接租约失效时返回 LEASE_LOST 并保持操作 claim 不动，等待超时收敛。 */
  @Test
  void publishOperationSuccess_lostLease_returnsLeaseLostAndLeavesClaimUntouched() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(), List.of());

    // 使用错误的 leaseToken
    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(),
            opId,
            ownerNodeId,
            UUID.randomUUID(),
            defaultSourceSetVersion,
            snapshot);

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.RUNNING, op.status(), "租约丢失时保持 RUNNING 状态以待超时清扫");
  }

  /** 测试意图：验证 publishExecutionFailure 原子将来源与操作标记为 FAILED 并写入常量 OPERATION_FAILED。 */
  @Test
  void publishExecutionFailure_marksSourceAndOperationFailed() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    OperationPublishOutcome outcome =
        publisher.publishExecutionFailure(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            defaultSourceId,
            0L);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    SkillSource src = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertNotNull(src);
    assertEquals(SkillSourceStatus.FAILED, src.getStatus());
    assertEquals(EnvironmentOperationFailureCodes.OPERATION_FAILED, src.getLastErrorCode());
    assertEquals(
        EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE, src.getLastErrorMessage());

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.OPERATION_FAILED, op.failureCode());
    assertEquals(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE, op.failureMessage());
  }

  /** 测试意图：验证 publishInvalidResult 原子将来源与操作标记为 FAILED 并写入常量 INVALID_RESULT。 */
  @Test
  void publishInvalidResult_marksSourceAndOperationFailed() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    OperationPublishOutcome outcome =
        publisher.publishInvalidResult(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            defaultSourceId,
            0L);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    SkillSource src = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertNotNull(src);
    assertEquals(SkillSourceStatus.FAILED, src.getStatus());
    assertEquals(EnvironmentOperationFailureCodes.INVALID_RESULT, src.getLastErrorCode());
    assertEquals(
        EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE, src.getLastErrorMessage());

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.INVALID_RESULT, op.failureCode());
    assertEquals(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE, op.failureMessage());
  }

  /** 测试意图：验证操作成功时若来源版本与快照版本不一致，原子推进操作至 FAILED (RESOURCE_CHANGED)。 */
  @Test
  void publishOperationSuccess_staleSourceVersion_marksResourceChanged() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    // 来源版本是 999L，而当前来源版本是 0L
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 999L, REVISION_A, List.of(), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            snapshot);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证操作成功时若快照来源标识不存在于环境中，原子推进操作至 FAILED (RESOURCE_CHANGED)。 */
  @Test
  void publishOperationSuccess_missingSource_marksResourceChanged() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    UUID nonExistentSourceId = UUID.randomUUID();
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(nonExistentSourceId, 0L, REVISION_A, List.of(), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            snapshot);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证操作成功发布时若环境不存在，返回 LEASE_LOST。 */
  @Test
  void publishOperationSuccess_nonExistentEnvironment_returnsLeaseLost() {
    UUID opId = UUID.randomUUID();
    UUID nonExistentEnvId = UUID.randomUUID();
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            nonExistentEnvId, opId, ownerNodeId, leaseToken, defaultSourceSetVersion, snapshot);

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);
  }

  /** 测试意图：验证失败终结发布时若环境不存在，返回 LEASE_LOST。 */
  @Test
  void publishFailure_nonExistentEnvironment_returnsLeaseLost() {
    UUID opId = UUID.randomUUID();
    UUID nonExistentEnvId = UUID.randomUUID();

    OperationPublishOutcome outcome =
        publisher.publishExecutionFailure(
            nonExistentEnvId,
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            defaultSourceId,
            0L);

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);
  }

  /** 测试意图：验证失败终结发布时若连接租约丢失（如节点或代币不符），返回 LEASE_LOST。 */
  @Test
  void publishFailure_lostLease_returnsLeaseLostAndLeavesClaimUntouched() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    // 错误的 leaseToken
    OperationPublishOutcome outcome =
        publisher.publishExecutionFailure(
            environmentId.value(),
            opId,
            ownerNodeId,
            UUID.randomUUID(),
            defaultSourceSetVersion,
            defaultSourceId,
            0L);

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.RUNNING, op.status(), "租约丢失时保持 RUNNING 状态以待超时清扫");
  }

  /** 测试意图：验证失败终结发布时若代际期望不匹配，原子推进操作至 FAILED (RESOURCE_CHANGED)。 */
  @Test
  void publishFailure_staleSourceSetVersion_marksResourceChanged() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    // 期望代际是 999L（陈旧）
    OperationPublishOutcome outcome =
        publisher.publishExecutionFailure(
            environmentId.value(), opId, ownerNodeId, leaseToken, 999L, defaultSourceId, 0L);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证失败终结发布时若目标来源不存在，原子推进操作至 FAILED (RESOURCE_CHANGED)。 */
  @Test
  void publishFailure_missingSource_marksResourceChanged() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    UUID nonExistentSourceId = UUID.randomUUID();
    OperationPublishOutcome outcome =
        publisher.publishExecutionFailure(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            nonExistentSourceId,
            0L);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证失败终结发布时若来源期望版本不符，原子推进操作至 FAILED (RESOURCE_CHANGED)。 */
  @Test
  void publishFailure_staleSourceVersion_marksResourceChanged() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    // 来源期望版本是 999L（当前为 0L）
    OperationPublishOutcome outcome =
        publisher.publishExecutionFailure(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            defaultSourceSetVersion,
            defaultSourceId,
            999L);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证失败终结时操作围栏校验失败（如操作已终结），抛出 IllegalStateException 回滚来源状态。 */
  @Test
  void publishFailure_rollsBackOnOperationMarkFailedFailure() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            defaultSourceSetVersion,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    // 预先将操作置为 UNKNOWN，导致后续 markFailed 影响 0 行
    operationRepository.markUnknown(opId, ownerNodeId, leaseToken, "TIMEOUT", "Timeout elapsed");

    assertThrows(
        IllegalStateException.class,
        () ->
            publisher.publishExecutionFailure(
                environmentId.value(),
                opId,
                ownerNodeId,
                leaseToken,
                defaultSourceSetVersion,
                defaultSourceId,
                0L));

    // 来源状态应在异常回滚后不保持 FAILED
    SkillSource src = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertFalse(src.getStatus() == SkillSourceStatus.FAILED, "事务回滚后来源状态不得为 FAILED");
  }
}
