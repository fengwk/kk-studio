package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 验证 {@link EnvironmentOperationResultPublisher} 的单元边界契约： 包括序列化失败时的降级回退、关键更新失败时的严格异常抛出及字段缺省分支。 */
class EnvironmentOperationResultPublisherTest {

  private EnvironmentRepository environmentRepository;
  private SkillSourceRepository skillSourceRepository;
  private EnvironmentOperationRepository operationRepository;
  private JdbcTemplate jdbcTemplate;
  private ObjectMapper objectMapper;
  private Clock clock;
  private EnvironmentOperationResultPublisher publisher;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();
  private final UUID sourceId = UUID.randomUUID();
  private static final String VALID_REVISION = "a".repeat(40);

  private DaemonCapabilities capabilities;
  private EnvironmentInventory inventory;
  private SkillSource source;

  @BeforeEach
  void setUp() {
    environmentRepository = mock(EnvironmentRepository.class);
    skillSourceRepository = mock(SkillSourceRepository.class);
    operationRepository = mock(EnvironmentOperationRepository.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    objectMapper = mock(ObjectMapper.class);
    clock = Clock.fixed(Instant.parse("2026-09-13T08:00:00Z"), ZoneOffset.UTC);

    publisher =
        new EnvironmentOperationResultPublisher(
            environmentRepository,
            skillSourceRepository,
            operationRepository,
            jdbcTemplate,
            clock,
            objectMapper);

    capabilities =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "Asia/Shanghai", "note", "/opt/root"),
            1L,
            List.of());

    inventory = new EnvironmentInventory();
    inventory.setEnvironmentId(envId);
    inventory.setSourceSetVersion(1L);

    source = new SkillSource();
    source.setEnvironmentId(envId);
    source.setSourceId(sourceId);
    source.setVersion(2L);
  }

  @SuppressWarnings("unchecked")
  private void stubSuccessfulPrerequisites() {
    when(environmentRepository.lockForKeyShare(envId)).thenReturn(new Environment());
    when(jdbcTemplate.query(
            anyString(), any(RowMapper.class), eq(envId), eq(nodeId), eq(leaseToken)))
        .thenReturn(List.of(capabilities));
    when(skillSourceRepository.lockInventory(envId)).thenReturn(inventory);
    when(skillSourceRepository.lockAllSources(envId)).thenReturn(List.of(source));
  }

  @SuppressWarnings("unchecked")
  private void stubFailurePrerequisites() {
    when(environmentRepository.lockForKeyShare(envId)).thenReturn(new Environment());
    when(jdbcTemplate.query(
            anyString(), any(RowMapper.class), eq(envId), eq(nodeId), eq(leaseToken)))
        .thenReturn(List.of(envId));
    when(skillSourceRepository.lockInventory(envId)).thenReturn(inventory);
    when(skillSourceRepository.lockAllSources(envId)).thenReturn(List.of(source));
  }

  /** 测试意图：验证构建成功摘要时若 ObjectMapper 抛出 JsonProcessingException，安全回退为 "{}" 且不阻断发布。 */
  @Test
  void publishOperationSuccess_fallsBackToEmptyObjectOnJsonProcessingException() throws Exception {
    stubSuccessfulPrerequisites();

    when(objectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("simulated serialization error") {});
    when(skillSourceRepository.markSourceReady(eq(envId), eq(sourceId), eq(2L), any(), any()))
        .thenReturn(true);
    when(skillSourceRepository.applyReadyReport(
            eq(envId),
            eq(1L),
            anyInt(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq(nodeId),
            eq(leaseToken)))
        .thenReturn(true);
    when(operationRepository.markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), eq("{}")))
        .thenReturn(true);

    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(sourceId, 2L, "demo-skill", "desc", "/skills", "0".repeat(64));
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(sourceId, 2L, VALID_REVISION, List.of(skill), List.of());

    OperationPublishOutcome outcome =
        publisher.publishOperationSuccess(envId, opId, nodeId, leaseToken, 1L, snapshot);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(operationRepository).markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), eq("{}"));
  }

  /** 测试意图：验证 markSourceReady 返回 false 时，抛出 IllegalStateException 阻止后续提交流程。 */
  @Test
  void publishOperationSuccess_throwsIllegalStateExceptionWhenMarkSourceReadyFails() {
    stubSuccessfulPrerequisites();

    when(skillSourceRepository.markSourceReady(eq(envId), eq(sourceId), eq(2L), any(), any()))
        .thenReturn(false);

    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(sourceId, 2L, VALID_REVISION, List.of(), List.of());

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> publisher.publishOperationSuccess(envId, opId, nodeId, leaseToken, 1L, snapshot));

    assertTrue(ex.getMessage().contains("mark skill source READY failed"));
  }

  /** 测试意图：验证 applyReadyReport 返回 false 时，抛出 IllegalStateException 阻止状态推进。 */
  @Test
  void publishOperationSuccess_throwsIllegalStateExceptionWhenApplyReadyReportFails() {
    stubSuccessfulPrerequisites();

    when(skillSourceRepository.markSourceReady(eq(envId), eq(sourceId), eq(2L), any(), any()))
        .thenReturn(true);
    when(skillSourceRepository.applyReadyReport(
            eq(envId),
            eq(1L),
            anyInt(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq(nodeId),
            eq(leaseToken)))
        .thenReturn(false);

    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(sourceId, 2L, VALID_REVISION, List.of(), List.of());

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> publisher.publishOperationSuccess(envId, opId, nodeId, leaseToken, 1L, snapshot));

    assertTrue(ex.getMessage().contains("apply skill inventory report failed"));
  }

  /** 测试意图：验证 markSucceeded 返回 false 时，抛出 IllegalStateException 阻止失效操作标记为成功。 */
  @Test
  void publishOperationSuccess_throwsIllegalStateExceptionWhenMarkSucceededFails()
      throws Exception {
    stubSuccessfulPrerequisites();

    when(objectMapper.writeValueAsString(any())).thenReturn("{\"skillCount\":0}");
    when(skillSourceRepository.markSourceReady(eq(envId), eq(sourceId), eq(2L), any(), any()))
        .thenReturn(true);
    when(skillSourceRepository.applyReadyReport(
            eq(envId),
            eq(1L),
            anyInt(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq(nodeId),
            eq(leaseToken)))
        .thenReturn(true);
    when(operationRepository.markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), anyString()))
        .thenReturn(false);

    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(sourceId, 2L, VALID_REVISION, List.of(), List.of());

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> publisher.publishOperationSuccess(envId, opId, nodeId, leaseToken, 1L, snapshot));

    assertTrue(ex.getMessage().contains("failed to mark operation succeeded"));
  }

  /** 测试意图：验证 publishFailure 中 markSourceFailed 返回 false 时抛出 IllegalStateException。 */
  @Test
  void publishFailure_throwsIllegalStateExceptionWhenMarkSourceFailedFails() {
    stubFailurePrerequisites();

    when(skillSourceRepository.markSourceFailed(
            eq(envId), eq(sourceId), eq(2L), anyString(), anyString()))
        .thenReturn(false);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                publisher.publishExecutionFailure(
                    envId, opId, nodeId, leaseToken, 1L, sourceId, 2L));

    assertTrue(ex.getMessage().contains("mark skill source FAILED failed"));
  }

  /** 测试意图：验证 publishFailure 中 markFailed 返回 false 时抛出 IllegalStateException。 */
  @Test
  void publishFailure_throwsIllegalStateExceptionWhenMarkFailedFails() {
    stubFailurePrerequisites();

    when(skillSourceRepository.markSourceFailed(
            eq(envId), eq(sourceId), eq(2L), anyString(), anyString()))
        .thenReturn(true);
    when(operationRepository.markFailed(
            eq(opId), eq(nodeId), eq(leaseToken), anyString(), anyString()))
        .thenReturn(false);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                publisher.publishExecutionFailure(
                    envId, opId, nodeId, leaseToken, 1L, sourceId, 2L));

    assertTrue(ex.getMessage().contains("failed to mark operation failed under valid claim fence"));
  }
}
