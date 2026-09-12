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
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;

import java.util.List;
import java.util.UUID;

/**
 * {@link EnvironmentOperationResultPublisher} 基于真实 PostgreSQL 的集成测试。
 *
 * <p>验证全局锁顺序、代际围栏、主机元数据刷新、终态围栏失败事务回滚与安全失败终结契约。
 */
class EnvironmentOperationResultPublisherIntegrationTest extends PostgresSpringTestSupport {

  private static final String DEFAULT_SOURCE_PATH = "~/.agents/skills";
  private static final String REVISION_A = "0123456789abcdef0123456789abcdef01234567";
  private static final String CONTENT_REV = "0".repeat(64);

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private SkillSourceRepository skillSourceRepository;
  @Autowired private EnvironmentOperationResultPublisher publisher;
  @Autowired private EnvironmentOperationRepository operationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();

  private EnvironmentId environmentId;
  private UUID defaultSourceId;
  private UUID ownerNodeId;
  private UUID leaseToken;

  @BeforeEach
  void setUp() {
    ownerNodeId = UUID.randomUUID();
    leaseToken = UUID.randomUUID();

    EnvironmentCreateDTO createDto = new EnvironmentCreateDTO();
    createDto.setName("env-" + UUID.randomUUID());
    environmentId = EnvironmentId.of(UUID.fromString(environmentService.create(createDto).getId()));

    List<EnvironmentSkillSourceDTO> sources = sourceService.list(environmentId);
    assertEquals(1, sources.size());
    defaultSourceId = UUID.fromString(sources.get(0).getSourceId());

    DaemonCapabilities caps =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "Asia/Shanghai", "test-host-note", "/opt/root"),
            0L,
            List.of());
    String runtimeInfoJson = capabilitiesCodec.encode(caps);

    jdbcTemplate.update(
        """
        insert into environment_connection (
            environment_id, owner_node_id, lease_token, status,
            runtime_info, last_seen_at, lease_until
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

  /** 测试意图：验证操作成功时不仅标记 SUCCEEDED 并替换 Skill，而且原子推进 inventory 已应用代际并刷新主机元数据。 */
  @Test
  void publishOperationSuccess_advancesInventoryAndHostMetadataAndTerminatesOperation() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);

    List<ClaimedOperation> claimed = operationRepository.claimPendingWithTimeout(ownerNodeId, 10);
    assertEquals(1, claimed.size());
    assertEquals(opId, claimed.getFirst().operation().id());

    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "demo-skill", "Demo skill desc", "/skills/demo", CONTENT_REV);
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(skill), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            0L,
            snapshot,
            "{\"skillCount\":1}");

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    EnvironmentInventory inv = skillSourceRepository.getInventory(environmentId.value());
    assertNotNull(inv);
    assertEquals(0L, inv.getAppliedSourceSetVersion(), "applied_source_set_version 必须已推进");
    assertEquals("linux", inv.getOperatingSystem());
    assertEquals("Asia/Shanghai", inv.getTimeZone());
    assertEquals("test-host-note", inv.getNote());
    assertEquals("/opt/root", inv.getRootPath());

    SkillSource src = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertNotNull(src);
    assertEquals(SkillSourceStatus.READY, src.getStatus());
    assertEquals(REVISION_A, src.getAppliedRevision());

    List<SkillInventoryEntry> skills = skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, skills.size());
    assertEquals("demo-skill", skills.getFirst().getName());

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
            0L,
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
                0L,
                snapshot,
                "{\"skillCount\":1}"));

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
            0L,
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
            environmentId.value(), opId, ownerNodeId, leaseToken, 999L, snapshot, "{}");

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
            0L,
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
            environmentId.value(), opId, ownerNodeId, UUID.randomUUID(), 0L, snapshot, "{}");

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.RUNNING, op.status(), "租约丢失时保持 RUNNING 状态以待超时清扫");
  }

  /** 测试意图：验证操作失败时原子将来源与操作标记为 FAILED 并写入脱敏原因。 */
  @Test
  void publishOperationFailure_marksSourceAndOperationFailed() {
    UUID opId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{\"type\":\"path\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    OperationPublishOutcome outcome =
        publisher.publishOperationFailure(
            environmentId.value(),
            opId,
            ownerNodeId,
            leaseToken,
            0L,
            defaultSourceId,
            0L,
            "TEST_FAIL_CODE",
            "Safe test failure message");

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    SkillSource src = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertNotNull(src);
    assertEquals(SkillSourceStatus.FAILED, src.getStatus());
    assertEquals("TEST_FAIL_CODE", src.getLastErrorCode());
    assertEquals("Safe test failure message", src.getLastErrorMessage());

    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals("TEST_FAIL_CODE", op.failureCode());
    assertEquals("Safe test failure message", op.failureMessage());
  }
}
