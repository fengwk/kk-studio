package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

import java.util.List;
import java.util.UUID;

/** 验证 {@link EnvironmentOperationService} 的参数校验、并发排他、环境隔离与取消边界契约。 */
class EnvironmentOperationServiceTest extends PostgresSpringTestSupport {

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentOperationService operationService;
  @Autowired private EnvironmentOperationRepository operationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private EnvironmentId environmentId;

  @BeforeEach
  void setUp() {
    EnvironmentCreateDTO createEnv = new EnvironmentCreateDTO();
    createEnv.setName("op-svc-env-" + System.nanoTime());
    environmentId = EnvironmentId.of(UUID.fromString(environmentService.create(createEnv).getId()));
  }

  /** 测试意图：验证资源行版本、超时与超描述符上限都被入口校验拒绝，绝不产生悬挂操作。 */
  @Test
  void createOperationValidatesVersionAndTimeout() {
    // 负 resourceVersion 被拒绝
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.createOperation(
                environmentId,
                EnvironmentOperationType.MCP_SERVER_DISCOVER,
                EnvironmentOperationResourceType.MCP_SERVER,
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
                EnvironmentOperationType.MCP_SERVER_DISCOVER,
                EnvironmentOperationResourceType.MCP_SERVER,
                UUID.randomUUID(),
                0L,
                "{}",
                "{}",
                0L));

    // 超过 mcp.local.discover 描述符上限的 timeoutMillis 被拒绝
    long overLimit =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER)
                .timeout()
                .toMillis()
            + 1L;
    assertThrows(
        AiValidationException.class,
        () ->
            operationService.createOperation(
                environmentId,
                EnvironmentOperationType.MCP_SERVER_DISCOVER,
                EnvironmentOperationResourceType.MCP_SERVER,
                UUID.randomUUID(),
                0L,
                "{}",
                "{}",
                overLimit));

    // 环境不存在时拒绝创建
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            operationService.createOperation(
                EnvironmentId.of(UUID.randomUUID()),
                EnvironmentOperationType.MCP_SERVER_DISCOVER,
                EnvironmentOperationResourceType.MCP_SERVER,
                UUID.randomUUID(),
                0L,
                "{}",
                "{}",
                60000L));
  }

  /** 测试意图：合法创建返回 PENDING 安全投影并冻结资源身份；同一资源已有活跃操作时拒绝重复并发。 */
  @Test
  void createPendingOperationSuccessfullyAndPreventsDuplicateActive() {
    UUID serverId = UUID.randomUUID();

    EnvironmentOperationDTO dto = createDiscovery(serverId, 3L);

    assertNotNull(dto);
    assertNotNull(dto.getId());
    assertEquals(environmentId.value().toString(), dto.getEnvironmentId());
    assertEquals("MCP_SERVER", dto.getResourceType());
    assertEquals(serverId.toString(), dto.getResourceId());
    assertEquals("MCP_SERVER_DISCOVER", dto.getOperationType());
    assertEquals("3", dto.getResourceVersion());
    assertEquals("PENDING", dto.getStatus());
    assertNotNull(dto.getDeadlineAt());
    assertNotNull(dto.getParameterSummary());

    // 同一资源重复创建活跃操作抛出 409
    assertThrows(AiDuplicateException.class, () -> createDiscovery(serverId, 3L));
  }

  /** 测试意图：验证 get 返回安全投影并强制环境隔离（错配环境返回 404）。 */
  @Test
  void getReturnsSafeDtoAndEnforcesEnvironmentIsolation() {
    EnvironmentOperationDTO created = createDiscovery(UUID.randomUUID(), 0L);

    EnvironmentOperationDTO fetched =
        operationService.get(environmentId, UUID.fromString(created.getId()));
    assertEquals(created.getId(), fetched.getId());

    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            operationService.get(
                EnvironmentId.of(UUID.randomUUID()), UUID.fromString(created.getId())));
  }

  /** 测试意图：list 拒绝非正 limit 与不存在的环境，并按倒序返回受 limit 约束的历史。 */
  @Test
  void listEnforcesValidationAndBoundsHistory() {
    assertThrows(AiValidationException.class, () -> operationService.list(environmentId, 0));
    assertThrows(AiValidationException.class, () -> operationService.list(environmentId, -1));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> operationService.list(EnvironmentId.of(UUID.randomUUID()), 10));

    EnvironmentOperationDTO first = createDiscovery(UUID.randomUUID(), 0L);
    operationService.cancel(environmentId, UUID.fromString(first.getId()));
    EnvironmentOperationDTO second = createDiscovery(UUID.randomUUID(), 0L);

    List<EnvironmentOperationDTO> list = operationService.list(environmentId, 10);
    assertEquals(2, list.size());
    assertEquals(second.getId(), list.get(0).getId());
    assertEquals(first.getId(), list.get(1).getId());

    List<EnvironmentOperationDTO> bounded = operationService.list(environmentId, 1);
    assertEquals(1, bounded.size());
    assertEquals(second.getId(), bounded.getFirst().getId());
  }

  /** 测试意图：cancel 只允许 PENDING 操作；环境错配 404，终态操作被拒绝。 */
  @Test
  void cancelRestrictedToPendingOperations() {
    EnvironmentOperationDTO created = createDiscovery(UUID.randomUUID(), 0L);
    UUID opId = UUID.fromString(created.getId());

    assertThrows(
        AiResourceNotFoundException.class,
        () -> operationService.cancel(EnvironmentId.of(UUID.randomUUID()), opId));

    EnvironmentOperationDTO cancelled = operationService.cancel(environmentId, opId);
    assertEquals("CANCELLED", cancelled.getStatus());

    assertThrows(AiValidationException.class, () -> operationService.cancel(environmentId, opId));
  }

  /** 测试意图：RUNNING 操作在认领期间不可被取消。 */
  @Test
  void cancelRejectsRunningOperation() {
    EnvironmentOperationDTO created = createDiscovery(UUID.randomUUID(), 0L);
    UUID opId = UUID.fromString(created.getId());

    jdbcTemplate.update(
        "UPDATE environment_operation SET status = 'RUNNING', owner_node_id = ?, lease_token = ?,"
            + " started_at = now() WHERE id = ?",
        UUID.randomUUID(),
        UUID.randomUUID(),
        opId);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> operationService.cancel(environmentId, opId));
    assertTrue(
        ex.getMessage().contains("operation cannot be cancelled because it is in status RUNNING"));
  }

  /** 测试意图：持久化层确实保存了冻结的私有参数与环境安全投影。 */
  @Test
  void createOperationPersistsFrozenArguments() throws Exception {
    UUID serverId = UUID.randomUUID();
    String arguments = "{\"serverId\":\"" + serverId + "\",\"configVersion\":0}";

    EnvironmentOperationDTO dto =
        operationService.createOperation(
            environmentId,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            serverId,
            0L,
            arguments,
            "{\"type\":\"local\"}",
            60000L);

    EnvironmentOperation stored = operationRepository.getById(UUID.fromString(dto.getId()));
    // jsonb 会规范化键序与空白，因此按 JSON 结构比较而不是按原文字符串比较。
    assertEquals(
        new ObjectMapper().readTree(arguments), new ObjectMapper().readTree(stored.arguments()));
    assertEquals("local", dto.getParameterSummary().get("type"));
  }

  private EnvironmentOperationDTO createDiscovery(UUID serverId, long resourceVersion) {
    return operationService.createOperation(
        environmentId,
        EnvironmentOperationType.MCP_SERVER_DISCOVER,
        EnvironmentOperationResourceType.MCP_SERVER,
        serverId,
        resourceVersion,
        "{\"serverId\":\"" + serverId + "\",\"configVersion\":" + resourceVersion + "}",
        "{\"type\":\"local\"}",
        60000L);
  }
}
