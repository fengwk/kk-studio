package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.mapper.McpServerMapper;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpServerDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpToolDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 基于 PostgreSQL 的 MCP server 与工具仓库实现。 */
@AllArgsConstructor
@Repository
public class PostgresqlMcpServerRepository implements McpServerRepository {

  private final McpServerMapper mapper;

  @Override
  public Page<McpServer> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<McpServerDO> results = mapper.page(offset, limit);
    return Pages.page(pageQuery, results, mapper.count()).map(this::convertServer);
  }

  @Override
  public List<McpServer> listAllServers() {
    return mapper.listAllServers().stream().map(this::convertServer).toList();
  }

  @Override
  public Optional<McpServer> getById(UUID id) {
    return Optional.ofNullable(convertServer(mapper.getById(id)));
  }

  @Override
  public Optional<McpServer> getByIdForUpdate(UUID id) {
    return Optional.ofNullable(convertServer(mapper.getByIdForUpdate(id)));
  }

  @Override
  public Optional<McpServer> getByName(String name) {
    return Optional.ofNullable(convertServer(mapper.getByName(name)));
  }

  @Override
  public boolean create(McpServer server) {
    return mapper.insert(convertServer(server)) == 1;
  }

  @Override
  public boolean updateById(McpServer server, long expectedVersion) {
    return mapper.updateById(convertServer(server), expectedVersion) == 1;
  }

  @Override
  public boolean updateDiscoveryResult(
      UUID id, long expectedVersion, McpDiscoveryStatus discoveryStatus, Long discoveredVersion) {
    return mapper.updateDiscoveryResult(
            id, expectedVersion, discoveryStatus.name(), discoveredVersion)
        == 1;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    return mapper.deleteById(id, expectedVersion) == 1;
  }

  @Override
  public Optional<McpTool> getToolById(UUID toolId) {
    return Optional.ofNullable(convertTool(mapper.getToolById(toolId)));
  }

  @Override
  public List<McpTool> listTools(UUID serverId) {
    return mapper.listTools(serverId).stream().map(this::convertTool).toList();
  }

  @Override
  public List<McpTool> listAvailableTools(UUID serverId) {
    return mapper.listAvailableTools(serverId).stream().map(this::convertTool).toList();
  }

  @Override
  public List<McpTool> listAllAvailableTools() {
    return mapper.listAllAvailableTools().stream().map(this::convertTool).toList();
  }

  @Override
  public int countAvailableTools(UUID serverId) {
    return mapper.countAvailableTools(serverId);
  }

  @Override
  public void insertTool(McpTool tool) {
    mapper.insertTool(convertTool(tool));
  }

  @Override
  public void updateTool(McpTool tool) {
    mapper.updateTool(convertTool(tool));
  }

  @Override
  public void deleteTool(UUID serverId, String sourceName) {
    mapper.deleteTool(serverId, sourceName);
  }

  @Override
  public boolean isModelNameTaken(String modelName, UUID excludingToolId) {
    long count =
        excludingToolId == null
            ? mapper.countByModelName(modelName)
            : mapper.countByModelNameExcluding(modelName, excludingToolId);
    return count > 0;
  }

  @Override
  public List<String> selectReferencedAgentToolIds() {
    return mapper.selectReferencedAgentToolIds();
  }

  private McpServerDO convertServer(McpServer server) {
    if (server == null) {
      return null;
    }
    McpServerDO result = new McpServerDO();
    result.setId(server.getId());
    result.setName(server.getName());
    result.setConnectionType(
        server.getConnectionType() == null ? null : server.getConnectionType().name());
    result.setEnvironmentId(server.getEnvironmentId());
    result.setConnectionConfig(server.getConnectionConfig());
    result.setEnabled(server.isEnabled());
    result.setTimeoutMillis(server.getTimeoutMillis());
    result.setDiscoveryStatus(
        server.getDiscoveryStatus() == null ? null : server.getDiscoveryStatus().name());
    result.setDiscoveredVersion(server.getDiscoveredVersion());
    result.setVersion(server.getVersion());
    result.setCreateTime(server.getCreateTime());
    result.setUpdateTime(server.getUpdateTime());
    return result;
  }

  private McpServer convertServer(McpServerDO server) {
    if (server == null) {
      return null;
    }
    McpServer result = new McpServer();
    result.setId(server.getId());
    result.setName(server.getName());
    result.setConnectionType(
        server.getConnectionType() == null
            ? null
            : McpConnectionType.valueOf(server.getConnectionType()));
    result.setEnvironmentId(server.getEnvironmentId());
    result.setConnectionConfig(server.getConnectionConfig());
    result.setEnabled(server.isEnabled());
    result.setTimeoutMillis(server.getTimeoutMillis());
    result.setDiscoveryStatus(
        server.getDiscoveryStatus() == null
            ? null
            : McpDiscoveryStatus.valueOf(server.getDiscoveryStatus()));
    result.setDiscoveredVersion(server.getDiscoveredVersion());
    result.setVersion(server.getVersion());
    result.setCreateTime(server.getCreateTime());
    result.setUpdateTime(server.getUpdateTime());
    return result;
  }

  private McpToolDO convertTool(McpTool tool) {
    if (tool == null) {
      return null;
    }
    McpToolDO result = new McpToolDO();
    result.setId(tool.getId());
    result.setServerId(tool.getServerId());
    result.setSourceName(tool.getSourceName());
    result.setModelName(tool.getModelName());
    result.setDescription(tool.getDescription());
    result.setInputSchemaJson(tool.getInputSchemaJson());
    result.setSchemaRevision(tool.getSchemaRevision());
    result.setAvailable(tool.isAvailable());
    return result;
  }

  private McpTool convertTool(McpToolDO tool) {
    if (tool == null) {
      return null;
    }
    McpTool result = new McpTool();
    result.setId(tool.getId());
    result.setServerId(tool.getServerId());
    result.setSourceName(tool.getSourceName());
    result.setModelName(tool.getModelName());
    result.setDescription(tool.getDescription());
    result.setInputSchemaJson(tool.getInputSchemaJson());
    result.setSchemaRevision(tool.getSchemaRevision());
    result.setAvailable(tool.isAvailable());
    return result;
  }
}
