package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 测试 {@link DefaultMcpDiscoveryResultPublisher} 的原子消费与 CAS/行锁语义。 */
class DefaultMcpDiscoveryResultPublisherTest {

  private EnvironmentOperationRepository operationRepository;
  private McpServerRepository mcpServerRepository;
  private EnvironmentRepository environmentRepository;
  private JdbcTemplate jdbcTemplate;
  private PlatformTransactionManager transactionManager;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private DefaultMcpDiscoveryResultPublisher publisher;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();
  private final UUID serverId = UUID.randomUUID();
  private final long resourceVersion = 2L;

  @BeforeEach
  void setUp() {
    operationRepository = mock(EnvironmentOperationRepository.class);
    mcpServerRepository = mock(McpServerRepository.class);
    environmentRepository = mock(EnvironmentRepository.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    when(environmentRepository.lockForKeyShare(envId)).thenReturn(new Environment());
    when(jdbcTemplate.query(
            anyString(), any(RowMapper.class), eq(envId), eq(nodeId), eq(leaseToken)))
        .thenReturn(List.of(envId));
    when(mcpServerRepository.updateDiscoveryResult(any(), anyLong(), any(), any()))
        .thenReturn(true);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    publisher =
        new DefaultMcpDiscoveryResultPublisher(
            operationRepository,
            mcpServerRepository,
            environmentRepository,
            jdbcTemplate,
            transactionManager,
            objectMapper);
  }

  @Test
  void publishDiscoverySuccess_appliesToolsAndMarksSucceeded() {
    // 意图：验证发现成功时正确新增工具、更新 Server 为 AVAILABLE、更新 operation 为 SUCCEEDED
    McpServer server = createLocalServer(resourceVersion);
    when(mcpServerRepository.getByIdForUpdate(serverId)).thenReturn(Optional.of(server));
    when(mcpServerRepository.listTools(serverId)).thenReturn(List.of());
    when(operationRepository.markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), any()))
        .thenReturn(true);

    String daemonOutput =
        """
        {
          "serverId": "%s",
          "configVersion": %d,
          "tools": [
            {
              "name": "read_file",
              "description": "Read file contents",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "path": {"type": "string"}
                },
                "required": ["path"]
              }
            }
          ]
        }
        """
            .formatted(serverId, resourceVersion);
    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json(opId.toString(), daemonOutput);

    OperationPublishOutcome outcome =
        publisher.publishDiscoverySuccess(
            envId, opId, nodeId, leaseToken, serverId, resourceVersion, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpServerRepository)
        .updateDiscoveryResult(
            eq(serverId),
            eq(resourceVersion),
            eq(McpDiscoveryStatus.AVAILABLE),
            eq(resourceVersion));

    ArgumentCaptor<McpTool> toolCaptor = ArgumentCaptor.forClass(McpTool.class);
    verify(mcpServerRepository).insertTool(toolCaptor.capture());
    McpTool tool = toolCaptor.getValue();
    assertEquals("read_file", tool.getSourceName());
    assertEquals("mcp_test_local_read_file", tool.getModelName());
    assertTrue(tool.isAvailable());
    assertEquals(0L, tool.getSchemaRevision());

    InOrder lockOrder =
        inOrder(environmentRepository, jdbcTemplate, mcpServerRepository, operationRepository);
    lockOrder.verify(environmentRepository).lockForKeyShare(envId);
    lockOrder
        .verify(jdbcTemplate)
        .query(anyString(), any(RowMapper.class), eq(envId), eq(nodeId), eq(leaseToken));
    lockOrder.verify(mcpServerRepository).getByIdForUpdate(serverId);
    lockOrder
        .verify(operationRepository)
        .markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), any());
  }

  @Test
  void publishDiscoverySuccess_tombstonesMissingToolsAndIncrementsReappearing() {
    // 意图：验证未在发现中出现的工具被打上 tombstone（available=false），重新出现的已下线工具 revision 递增
    McpServer server = createLocalServer(resourceVersion);
    when(mcpServerRepository.getByIdForUpdate(serverId)).thenReturn(Optional.of(server));

    McpTool existingTool1 = new McpTool();
    existingTool1.setId(UUID.randomUUID());
    existingTool1.setServerId(serverId);
    existingTool1.setSourceName("tool_one");
    existingTool1.setModelName("mcp_test_local_tool_one");
    existingTool1.setDescription("Tool one");
    existingTool1.setInputSchemaJson("{\"type\":\"object\"}");
    existingTool1.setAvailable(false); // previously tombstoned
    existingTool1.setSchemaRevision(0L);

    McpTool existingTool2 = new McpTool();
    existingTool2.setId(UUID.randomUUID());
    existingTool2.setServerId(serverId);
    existingTool2.setSourceName("tool_two");
    existingTool2.setModelName("mcp_test_local_tool_two");
    existingTool2.setDescription("Tool two");
    existingTool2.setInputSchemaJson("{\"type\":\"object\"}");
    existingTool2.setAvailable(true);
    existingTool2.setSchemaRevision(0L);

    when(mcpServerRepository.listTools(serverId)).thenReturn(List.of(existingTool1, existingTool2));
    when(operationRepository.markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), any()))
        .thenReturn(true);

    // Discovery only returns tool_one; tool_two disappears
    String daemonOutput =
        """
        {
          "serverId": "%s",
          "configVersion": %d,
          "tools": [
            {
              "name": "tool_one",
              "description": "Tool one",
              "inputSchema": {"type": "object"}
            }
          ]
        }
        """
            .formatted(serverId, resourceVersion);
    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json(opId.toString(), daemonOutput);

    OperationPublishOutcome outcome =
        publisher.publishDiscoverySuccess(
            envId, opId, nodeId, leaseToken, serverId, resourceVersion, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    ArgumentCaptor<McpTool> toolCaptor = ArgumentCaptor.forClass(McpTool.class);
    verify(mcpServerRepository, times(2)).updateTool(toolCaptor.capture());
    List<McpTool> updatedTools = toolCaptor.getAllValues();

    McpTool revived =
        updatedTools.stream().filter(t -> t.getSourceName().equals("tool_one")).findFirst().get();
    assertTrue(revived.isAvailable());
    assertEquals(1L, revived.getSchemaRevision()); // incremented from 0 to 1

    McpTool tombstoned =
        updatedTools.stream().filter(t -> t.getSourceName().equals("tool_two")).findFirst().get();
    assertFalse(tombstoned.isAvailable());
    assertEquals(0L, tombstoned.getSchemaRevision()); // tombstoned retains revision
  }

  @Test
  void publishDiscoverySuccess_versionMismatch_marksFailedWithResourceChanged() {
    // 意图：验证 CAS 版本漂移时收敛为 RESOURCE_CHANGED 失败
    McpServer server = createLocalServer(resourceVersion + 1); // Drifted!
    when(mcpServerRepository.getByIdForUpdate(serverId)).thenReturn(Optional.of(server));
    when(operationRepository.markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.RESOURCE_CHANGED),
            any()))
        .thenReturn(true);

    String daemonOutput =
        """
        {
          "serverId": "%s",
          "configVersion": %d,
          "tools": []
        }
        """
            .formatted(serverId, resourceVersion);
    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json(opId.toString(), daemonOutput);
    OperationPublishOutcome outcome =
        publisher.publishDiscoverySuccess(
            envId, opId, nodeId, leaseToken, serverId, resourceVersion, result);

    assertEquals(OperationPublishOutcome.RESOURCE_CHANGED, outcome);
    verify(operationRepository)
        .markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.RESOURCE_CHANGED),
            any());
  }

  @Test
  void publishDiscoveryFailure_marksOperationFailedAndServerToFailed() {
    // 意图：验证发现失败原子推进操作为 FAILED 并将 Server 标记为 FAILED
    McpServer server = createLocalServer(resourceVersion);
    when(mcpServerRepository.getByIdForUpdate(serverId)).thenReturn(Optional.of(server));
    when(operationRepository.markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            any()))
        .thenReturn(true);

    OperationPublishOutcome outcome =
        publisher.publishDiscoveryFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            serverId,
            resourceVersion,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            "Spawn failed");

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpServerRepository)
        .updateDiscoveryResult(
            eq(serverId), eq(resourceVersion), eq(McpDiscoveryStatus.FAILED), eq(null));
  }

  @Test
  void publishDiscoverySuccess_rollsBackCatalogWhenTerminalFenceFails() {
    // 意图：验证工具与 Server 已写入后 operation 终态 CAS 失败会回滚整个目录事务
    McpServer server = createLocalServer(resourceVersion);
    when(mcpServerRepository.getByIdForUpdate(serverId)).thenReturn(Optional.of(server));
    when(mcpServerRepository.listTools(serverId)).thenReturn(List.of());
    when(operationRepository.markSucceeded(eq(opId), eq(nodeId), eq(leaseToken), any()))
        .thenReturn(false);
    String daemonOutput =
        """
        {
          "serverId": "%s",
          "configVersion": %d,
          "tools": [{
            "name": "read_file",
            "description": "Read file contents",
            "inputSchema": {"type": "object"}
          }]
        }
        """
            .formatted(serverId, resourceVersion);

    OperationPublishOutcome outcome =
        publisher.publishDiscoverySuccess(
            envId,
            opId,
            nodeId,
            leaseToken,
            serverId,
            resourceVersion,
            EnvironmentCapabilityResult.json(opId.toString(), daemonOutput));

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);
    verify(mcpServerRepository).insertTool(any());
    verify(mcpServerRepository)
        .updateDiscoveryResult(
            serverId, resourceVersion, McpDiscoveryStatus.AVAILABLE, resourceVersion);
    verify(transactionManager).rollback(any());
  }

  @Test
  void publishDiscoveryFailure_rollsBackServerWhenTerminalFenceFails() {
    // 意图：验证失败状态已写 Server 后 operation 终态 CAS 失败同样回滚整个事务
    McpServer server = createLocalServer(resourceVersion);
    when(mcpServerRepository.getByIdForUpdate(serverId)).thenReturn(Optional.of(server));
    when(operationRepository.markFailed(
            opId, nodeId, leaseToken, EnvironmentOperationFailureCodes.OPERATION_FAILED, "failed"))
        .thenReturn(false);

    OperationPublishOutcome outcome =
        publisher.publishDiscoveryFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            serverId,
            resourceVersion,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            "failed");

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);
    verify(mcpServerRepository)
        .updateDiscoveryResult(serverId, resourceVersion, McpDiscoveryStatus.FAILED, null);
    verify(transactionManager).rollback(any());
  }

  private McpServer createLocalServer(long version) {
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("test_local");
    server.setConnectionType(McpConnectionType.LOCAL);
    server.setEnvironmentId(envId);
    server.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);
    server.setVersion(version);
    server.setConnectionConfig("{\"command\":[\"node\",\"index.js\"],\"cwd\":\"/app\",\"env\":{}}");
    server.setTimeoutMillis(5000L);
    return server;
  }
}
