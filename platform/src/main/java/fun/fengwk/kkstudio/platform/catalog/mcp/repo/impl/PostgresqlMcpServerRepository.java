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
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;

/** 基于 PostgreSQL 的 MCP server 与当前发现结果仓库实现。 */
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
  public Optional<McpServer> getByName(String name) {
    return Optional.ofNullable(convertServer(mapper.getByName(name)));
  }

  @Override
  public Optional<McpServer> getForUpdate(String name) {
    return Optional.ofNullable(convertServer(mapper.getForUpdate(name)));
  }

  @Override
  public boolean create(McpServer server) {
    return mapper.insert(convertServer(server)) == 1;
  }

  @Override
  public boolean update(McpServer server, long expectedVersion) {
    return mapper.update(convertServer(server), expectedVersion) == 1;
  }

  @Override
  public boolean updateDiscoveryStatus(
      String name, long expectedVersion, McpDiscoveryStatus status) {
    return mapper.updateDiscoveryStatus(name, expectedVersion, status.name()) == 1;
  }

  @Override
  public boolean delete(String name, long expectedVersion) {
    return mapper.delete(name, expectedVersion) == 1;
  }

  @Override
  public Optional<McpTool> getTool(String name) {
    return Optional.ofNullable(convertTool(mapper.getTool(name)));
  }

  @Override
  public List<McpTool> listTools(String serverName) {
    return mapper.listTools(serverName).stream().map(this::convertTool).toList();
  }

  @Override
  public List<McpTool> listAllTools() {
    return mapper.listAllTools().stream().map(this::convertTool).toList();
  }

  @Override
  public int countTools(String serverName) {
    return mapper.countTools(serverName);
  }

  @Override
  public void deleteTools(String serverName) {
    mapper.deleteTools(serverName);
  }

  @Override
  public void insertTool(McpTool tool) {
    mapper.insertTool(convertTool(tool));
  }

  @Override
  public List<String> selectReferencedToolNames() {
    return mapper.selectReferencedToolNames();
  }

  private McpServerDO convertServer(McpServer server) {
    if (server == null) {
      return null;
    }
    McpServerDO result = new McpServerDO();
    result.setName(server.getName());
    result.setUrl(server.getUrl());
    result.setHeadersJson(McpHeadersJson.encode(server.getHeaders()));
    result.setEnabled(server.isEnabled());
    result.setTimeoutMillis(server.getTimeoutMillis());
    result.setDiscoveryStatus(
        server.getDiscoveryStatus() == null ? null : server.getDiscoveryStatus().name());
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
    result.setName(server.getName());
    result.setUrl(server.getUrl());
    result.setHeaders(McpHeadersJson.decode(server.getHeadersJson()));
    result.setEnabled(server.isEnabled());
    result.setTimeoutMillis(server.getTimeoutMillis());
    result.setDiscoveryStatus(
        server.getDiscoveryStatus() == null
            ? null
            : McpDiscoveryStatus.valueOf(server.getDiscoveryStatus()));
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
    result.setName(tool.getName());
    result.setServerName(tool.getServerName());
    result.setSourceName(tool.getSourceName());
    result.setDescription(tool.getDescription());
    result.setInputSchemaJson(tool.getInputSchemaJson());
    return result;
  }

  private McpTool convertTool(McpToolDO tool) {
    if (tool == null) {
      return null;
    }
    McpTool result = new McpTool();
    result.setName(tool.getName());
    result.setServerName(tool.getServerName());
    result.setSourceName(tool.getSourceName());
    result.setDescription(tool.getDescription());
    result.setInputSchemaJson(tool.getInputSchemaJson());
    return result;
  }
}
