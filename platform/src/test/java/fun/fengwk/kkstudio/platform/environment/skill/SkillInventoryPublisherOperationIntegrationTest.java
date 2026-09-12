package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.environment.operation.ClaimedOperation;
import fun.fengwk.kkstudio.platform.environment.operation.CreatePendingOperationWithTimeoutCommand;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperation;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationFailureCodes;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationRepository;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationStatus;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationType;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;

import java.util.List;
import java.util.UUID;

/**
 * 验证 {@link SkillInventoryPublisher} 针对操作执行结果的围栏发布契约： 包含成功与失败路径的原子持久化、配置代际过时推进至
 * RESOURCE_CHANGED、租约失效保持 claim 不动等核心行为。
 */
class SkillInventoryPublisherOperationIntegrationTest extends PostgresSpringTestSupport {

  private static final String REVISION = "abcdef0123456789abcdef0123456789abcdef01";
  private static final String CONTENT_REV = "0".repeat(64);

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private SkillSourceRepository skillSourceRepository;
  @Autowired private EnvironmentOperationRepository operationRepository;
  @Autowired private SkillInventoryPublisher publisher;
  @Autowired private JdbcTemplate jdbcTemplate;

  private EnvironmentId environmentId;
  private UUID defaultSourceId;
  private UUID ownerNodeId;
  private UUID leaseToken;

  @BeforeEach
  void setUp() {
    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("op-pub-env-" + System.nanoTime());
    environmentId = EnvironmentId.of(UUID.fromString(environmentService.create(create).getId()));

    List<EnvironmentSkillSourceDTO> sources = sourceService.list(environmentId);
    defaultSourceId = UUID.fromString(sources.getFirst().getSourceId());

    ownerNodeId = UUID.randomUUID();
    leaseToken = UUID.randomUUID();
    insertLiveConnection(ownerNodeId, leaseToken, "READY", 300);
  }

  /** 测试意图：验证当连接租约有效且配置代际匹配时，publishOperationSuccess 原子持久化新技能行、置来源 READY，并终结操作为 SUCCEEDED。 */
  @Test
  void publishOperationSuccessAppliesAtomicallyUnderValidClaim() {
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
            "{\"type\":\"PATH\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);

    List<ClaimedOperation> claimed = operationRepository.claimPendingWithTimeout(ownerNodeId, 10);
    assertEquals(1, claimed.size());

    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "my-skill", "desc", "/skills/my-skill", CONTENT_REV);
    DaemonSkillDiagnostic diag = new DaemonSkillDiagnostic("/skills", "ok");
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION, List.of(skill), List.of(diag));

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

    // 验证技能行已写入
    List<SkillInventoryEntry> skills = skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, skills.size());
    assertEquals("my-skill", skills.getFirst().getName());

    // 验证来源状态
    SkillSource source = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertEquals("READY", source.getStatus().name());
    assertEquals(REVISION, source.getAppliedRevision());

    // 验证操作终态为 SUCCEEDED
    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.SUCCEEDED, op.status());
    assertNotNull(op.finishedAt());
    assertEquals("{\"skillCount\": 1}", op.resultSummary());
  }

  /** 测试意图：验证当前配置与冻结代际不匹配但租约仍有效时，操作原子推进至 FAILED (RESOURCE_CHANGED)，技能行不被修改。 */
  @Test
  void publishOperationSuccessMarksResourceChangedWhenConfigStale() {
    UUID opId = UUID.randomUUID();
    // 故意使用过时的 sourceSetVersion (期望 0，传入 99)
    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            environmentId.value(),
            defaultSourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            99L,
            "{}",
            "{\"type\":\"PATH\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "stale-skill", "desc", "/skills/stale", CONTENT_REV);
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION, List.of(skill), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(), opId, ownerNodeId, leaseToken, 99L, snapshot, "{}");

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);

    // 验证未写入技能
    assertTrue(skillSourceRepository.listSkills(environmentId.value()).isEmpty());

    // 验证操作推进至 FAILED (RESOURCE_CHANGED)
    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.RESOURCE_CHANGED, op.failureCode());
  }

  /** 测试意图：验证当租约失效（如租约过期或所属节点不匹配）时，返回 LEASE_LOST，操作保持 RUNNING 不动以供 UNKNOWN 收敛。 */
  @Test
  void publishOperationSuccessLeavesClaimUntouchedWhenLeaseLost() {
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
            "{\"type\":\"PATH\"}",
            60000L);
    operationRepository.createPendingWithTimeout(cmd);
    operationRepository.claimPendingWithTimeout(ownerNodeId, 10);

    UUID wrongNode = UUID.randomUUID();
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION, List.of(), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(
            environmentId.value(), opId, wrongNode, leaseToken, 0L, snapshot, "{}");

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);

    // 操作保持 RUNNING 不动
    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.RUNNING, op.status());
  }

  /** 测试意图：验证业务失败时 publishOperationFailure 原子将来源置 FAILED 并将操作置 FAILED。 */
  @Test
  void publishOperationFailureMarksSourceAndOperationFailed() {
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
            "{\"type\":\"PATH\"}",
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
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE,
            "{}");

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    // 验证来源置 FAILED
    SkillSource source = skillSourceRepository.getSource(environmentId.value(), defaultSourceId);
    assertEquals("FAILED", source.getStatus().name());
    assertEquals(EnvironmentOperationFailureCodes.OPERATION_FAILED, source.getLastErrorCode());

    // 验证操作置 FAILED
    EnvironmentOperation op = operationRepository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op.status());
    assertEquals(EnvironmentOperationFailureCodes.OPERATION_FAILED, op.failureCode());
  }

  private void insertLiveConnection(
      UUID ownerNodeId, UUID leaseToken, String status, int leaseSeconds) {
    jdbcTemplate.update(
        "insert into environment_connection (environment_id, owner_node_id, lease_token,"
            + " status, runtime_info, last_seen_at, lease_until) values (?, ?, ?, ?,"
            + " case when ? = 'READY' then '{}'::jsonb else null end,"
            + " statement_timestamp(), statement_timestamp() + (? * interval '1 second'))"
            + " on conflict (environment_id) do update set owner_node_id = excluded.owner_node_id,"
            + " lease_token = excluded.lease_token, status = excluded.status, lease_until = excluded.lease_until",
        environmentId.value(),
        ownerNodeId,
        leaseToken,
        status,
        status,
        leaseSeconds);
  }
}
