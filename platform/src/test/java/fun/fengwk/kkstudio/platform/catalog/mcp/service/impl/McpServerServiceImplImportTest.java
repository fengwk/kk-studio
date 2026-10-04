package fun.fengwk.kkstudio.platform.catalog.mcp.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试意图：锁定配置同步专用的 {@code importServer} 边界——新建用 UNVERIFIED 起点并按发现结果整体写入工具；既有行按 CAS 更新，
 * 过期版本冲突；同名插入冲突转为业务重复错误；被 Agent 引用的工具将消失时 fail closed 拒绝整次替换。
 */
class McpServerServiceImplImportTest {

  private static final String URL = "https://mcp.example.com/mcp";

  private final McpServerRepository repository = mock(McpServerRepository.class);
  private final McpServerServiceImpl service =
      new McpServerServiceImpl(
          repository, mock(McpToolDiscovery.class), mock(PlatformTransactionManager.class));

  private static McpTool tool(String name) {
    McpTool tool = new McpTool();
    tool.setName(name);
    tool.setServerName("mcp");
    tool.setSourceName(name);
    return tool;
  }

  private static McpServer existing(long version) {
    McpServer server = new McpServer();
    server.setName("mcp");
    server.setUrl(URL);
    server.setEnabled(true);
    server.setTimeoutMillis(30_000L);
    server.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);
    server.setVersion(version);
    return server;
  }

  @Test
  void newServerWithDiscoveryInsertsToolsAndMarksAvailable() {
    when(repository.getForUpdate("mcp")).thenReturn(Optional.empty());
    when(repository.create(any())).thenReturn(true);
    when(repository.updateDiscoveryStatus("mcp", 0L, McpDiscoveryStatus.AVAILABLE))
        .thenReturn(true);
    McpTool discovered = tool("mcp_mcp_echo");

    McpServerDTO dto =
        service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, List.of(discovered));

    verify(repository).insertTool(discovered);
    assertEquals("AVAILABLE", dto.getDiscoveryStatus());
    assertEquals(1, dto.getToolCount());
    assertEquals("0", dto.getVersion());
  }

  @Test
  void newServerWithoutDiscoveryStaysUnverified() {
    when(repository.getForUpdate("mcp")).thenReturn(Optional.empty());
    when(repository.create(any())).thenReturn(true);

    McpServerDTO dto = service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, null);

    verify(repository, never()).insertTool(any());
    assertEquals("UNVERIFIED", dto.getDiscoveryStatus());
    assertEquals(0, dto.getToolCount());
  }

  @Test
  void duplicateNameOnCreateIsRejected() {
    when(repository.getForUpdate("mcp")).thenReturn(Optional.empty());
    when(repository.create(any())).thenThrow(new DuplicateKeyException("duplicate"));

    assertThrows(
        AiDuplicateException.class,
        () -> service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, null));
  }

  @Test
  void existingServerWithStaleVersionConflicts() {
    McpServer existing = existing(0L);
    when(repository.getForUpdate("mcp")).thenReturn(Optional.of(existing));
    when(repository.update(existing, 0L)).thenReturn(false);

    assertThrows(
        AiVersionConflictException.class,
        () -> service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, null));
  }

  @Test
  void existingServerRemovingReferencedToolFailsClosed() {
    McpServer existing = existing(0L);
    when(repository.getForUpdate("mcp")).thenReturn(Optional.of(existing));
    when(repository.update(existing, 0L)).thenReturn(true);
    when(repository.listTools("mcp")).thenReturn(List.of(tool("mcp_mcp_keep")));
    when(repository.selectReferencedToolNames()).thenReturn(List.of("mcp_mcp_keep"));

    assertThrows(
        AiInUseException.class,
        () ->
            service.importServer(
                "mcp", URL, Map.of(), Boolean.TRUE, 30_000L, List.of(tool("mcp_mcp_next"))));
  }

  @Test
  void newServerCreateFailureIsRejected() {
    when(repository.getForUpdate("mcp")).thenReturn(Optional.empty());
    when(repository.create(any())).thenReturn(false);

    assertThrows(
        IllegalStateException.class,
        () -> service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, null));
  }

  @Test
  void newServerDiscoveryStatusCasConflictIsRejected() {
    when(repository.getForUpdate("mcp")).thenReturn(Optional.empty());
    when(repository.create(any())).thenReturn(true);
    when(repository.updateDiscoveryStatus("mcp", 0L, McpDiscoveryStatus.AVAILABLE))
        .thenReturn(false);

    assertThrows(
        AiVersionConflictException.class,
        () ->
            service.importServer(
                "mcp", URL, Map.of(), Boolean.TRUE, 30_000L, List.of(tool("mcp_mcp_echo"))));
  }

  @Test
  void existingServerWithoutDiscoveryCountsPersistedTools() {
    McpServer existing = existing(0L);
    when(repository.getForUpdate("mcp")).thenReturn(Optional.of(existing));
    when(repository.update(existing, 0L)).thenReturn(true);
    when(repository.listTools("mcp")).thenReturn(List.of(tool("mcp_mcp_old")));

    McpServerDTO dto = service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, null);

    assertEquals("UNVERIFIED", dto.getDiscoveryStatus());
    assertEquals(1, dto.getToolCount());
  }

  @Test
  void existingServerDiscoveryStatusCasConflictIsRejected() {
    McpServer existing = existing(0L);
    when(repository.getForUpdate("mcp")).thenReturn(Optional.of(existing));
    when(repository.update(existing, 0L)).thenReturn(true);
    when(repository.listTools("mcp")).thenReturn(List.of());
    when(repository.selectReferencedToolNames()).thenReturn(List.of());
    when(repository.updateDiscoveryStatus("mcp", 1L, McpDiscoveryStatus.AVAILABLE))
        .thenReturn(false);

    assertThrows(
        AiVersionConflictException.class,
        () ->
            service.importServer(
                "mcp", URL, Map.of(), Boolean.TRUE, 30_000L, List.of(tool("mcp_mcp_new"))));
  }

  @Test
  void existingServerReplacesToolsAndMarksAvailable() {
    McpServer existing = existing(0L);
    when(repository.getForUpdate("mcp")).thenReturn(Optional.of(existing));
    when(repository.update(existing, 0L)).thenReturn(true);
    when(repository.listTools("mcp")).thenReturn(List.of(tool("mcp_mcp_old")));
    when(repository.selectReferencedToolNames()).thenReturn(List.of());
    when(repository.updateDiscoveryStatus("mcp", 1L, McpDiscoveryStatus.AVAILABLE))
        .thenReturn(true);
    McpTool replacement = tool("mcp_mcp_new");

    McpServerDTO dto =
        service.importServer("mcp", URL, Map.of(), Boolean.TRUE, 30_000L, List.of(replacement));

    verify(repository).deleteTools("mcp");
    verify(repository).insertTool(replacement);
    assertEquals("AVAILABLE", dto.getDiscoveryStatus());
    assertEquals(1, dto.getToolCount());
  }
}
