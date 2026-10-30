package fun.fengwk.kkstudio.platform.catalog.mcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser.HttpConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Platform MCP server 应用服务：name-keyed CRUD/CAS、显式发现与引用保护。
 *
 * <p>发现的网络 I/O 与工具名校验全部发生在事务之外，事务内只在父 Server 行锁下原子替换当前工具行；被 Agent 引用的工具名若将消失则 fail closed
 * 并完整保留旧快照。公开 DTO 绝不回显 URL 与 headers。
 */
@Service
public class McpServerService {

  private static final String RESOURCE = "mcp_server";

  private final McpServerRepository repository;
  private final McpToolDiscovery toolDiscovery;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  public McpServerService(
      McpServerRepository repository, PlatformTransactionManager transactionManager) {
    this(repository, new McpToolDiscovery(), transactionManager);
  }

  public McpServerService(
      McpServerRepository repository,
      McpToolDiscovery toolDiscovery,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.toolDiscovery = Objects.requireNonNull(toolDiscovery, "toolDiscovery");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  public Page<McpServerDTO> pageServers(PageQuery pageQuery) {
    return repository
        .page(pageQuery)
        .map(server -> toDto(server, repository.countTools(server.name())));
  }

  /** 按不可变 name 获取 Server 安全公开视图。 */
  public McpServerDTO getServer(String name) {
    return toDto(requireServer(name), repository.countTools(name));
  }

  /** 读取 Server 显式 HTTP 配置；headers 可能内嵌凭据，调用方必须禁止缓存。 */
  public McpServerConfigDTO getServerConfig(String name) {
    McpServer server = requireServer(name);
    McpServerConfigDTO configDTO = new McpServerConfigDTO();
    configDTO.setName(server.name());
    configDTO.setVersion(CatalogVersions.format(server.version()));
    configDTO.setUrl(server.url());
    configDTO.setHeaders(server.headers());
    return configDTO;
  }

  /** 创建 Server 配置：仅校验并持久化为 UNVERIFIED，不发起任何网络 I/O。 */
  public McpServerDTO createServer(McpServerCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String name = McpConfigParser.requireName(createDTO.getName());
    HttpConfig config =
        McpConfigParser.normalizeHttpConfig(
            createDTO.getUrl(),
            createDTO.getHeaders(),
            createDTO.getEnabled(),
            createDTO.getTimeoutMillis());
    if (repository.getByName(name).isPresent()) {
      throw new AiDuplicateException(RESOURCE, "mcp server name already exists: " + name);
    }
    McpServer server = insert(name, config);
    return toDto(server, 0);
  }

  /** 基于 (name, expectedVersion) 的 CAS 全量更新：仅更新配置并重置状态为 UNVERIFIED，不发起发现。 */
  public McpServerDTO updateServer(String name, McpServerUpdateDTO updateDTO) {
    McpConfigParser.requireName(name);
    if (updateDTO == null || updateDTO.getExpectedVersion() == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion must not be null");
    }
    String rawExpectedVersion = updateDTO.getExpectedVersion();
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");
    HttpConfig config =
        McpConfigParser.normalizeHttpConfig(
            updateDTO.getUrl(),
            updateDTO.getHeaders(),
            updateDTO.getEnabled(),
            updateDTO.getTimeoutMillis());

    McpServer updatedServer =
        transactionTemplate.execute(
            status -> {
              McpServer locked = requireServerForUpdate(name);
              requireVersion(locked, expectedVersion, rawExpectedVersion);
              McpServer updated = withConfig(locked, config);
              if (!repository.update(updated, expectedVersion)) {
                throw new AiVersionConflictException(
                    RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.version()));
              }
              return updated.toBuilder().version(expectedVersion + 1).build();
            });
    return toDto(updatedServer, repository.countTools(name));
  }

  /** 基于 (name, expectedVersion) 的显式发现：同步握手并原子替换当前工具行。 */
  public McpServerDTO discoverServer(String name, String rawExpectedVersion) {
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");
    McpServer server = requireServer(name);
    requireVersion(server, expectedVersion, rawExpectedVersion);

    // 网络与工具名校验全部发生在事务之外：失败绝不触碰既有工具行。
    List<McpTool> discovered = toolDiscovery.discover(server);

    McpServer appliedServer =
        transactionTemplate.execute(
            status -> {
              McpServer locked = requireServerForUpdate(name);
              requireVersion(locked, expectedVersion, rawExpectedVersion);
              replaceTools(locked, discovered);
              return locked.toBuilder().discoveryStatus(McpDiscoveryStatus.AVAILABLE).build();
            });
    return toDto(appliedServer, discovered.size());
  }

  /**
   * 配置同步导入：按名称新增或更新 Server 配置，并写入调用方在事务外准备好的发现结果。
   *
   * <p>{@code discoveredTools} 为 {@code null} 表示发现失败或未执行：只保存配置并标记 {@code UNVERIFIED}，绝不伪造工具结果；
   * 成功时整体物理替换当前工具行并标记 {@code AVAILABLE}。发现的网络 I/O 必须在事务外完成。
   */
  @Transactional
  public McpServerDTO importServer(
      String name,
      String url,
      Map<String, String> headers,
      Boolean enabled,
      Long timeoutMillis,
      List<McpTool> discoveredTools) {
    String canonicalName = McpConfigParser.requireName(name);
    HttpConfig config = McpConfigParser.normalizeHttpConfig(url, headers, enabled, timeoutMillis);
    McpServer existing = repository.getForUpdate(canonicalName).orElse(null);
    McpServer server;
    if (existing == null) {
      server = insert(canonicalName, config);
    } else {
      long expected = existing.version();
      server = withConfig(existing, config);
      if (!repository.update(server, expected)) {
        throw new AiVersionConflictException(
            RESOURCE, CatalogVersions.format(expected), CatalogVersions.format(expected));
      }
      server = server.toBuilder().version(expected + 1).build();
    }
    if (discoveredTools == null) {
      return toDto(server, repository.countTools(canonicalName));
    }
    replaceTools(server, discoveredTools);
    return toDto(
        server.toBuilder().discoveryStatus(McpDiscoveryStatus.AVAILABLE).build(),
        discoveredTools.size());
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS：被 agent 引用时拒绝删除。 */
  public void deleteServer(String name, String rawExpectedVersion) {
    McpConfigParser.requireName(name);
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");

    transactionTemplate.execute(
        status -> {
          McpServer locked = requireServerForUpdate(name);
          requireVersion(locked, expectedVersion, rawExpectedVersion);
          List<String> referenced = repository.selectReferencedToolNames();
          for (McpTool tool : repository.listTools(name)) {
            if (referenced.contains(tool.name())) {
              throw new AiInUseException(
                  RESOURCE, "mcp tool " + tool.name() + " is currently referenced by an agent");
            }
          }
          if (!repository.delete(name, expectedVersion)) {
            throw new AiVersionConflictException(
                RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.version()));
          }
          return null;
        });
  }

  /** 在父 Server 行锁下整体物理替换当前工具行并标记 AVAILABLE；被 Agent 引用的旧工具名若将消失则 fail closed，不存在 tombstone 或修订版本。 */
  private void replaceTools(McpServer locked, List<McpTool> discovered) {
    String name = locked.name();
    Set<String> nextNames = discovered.stream().map(McpTool::name).collect(Collectors.toSet());
    Set<String> referencedNames = new HashSet<>(repository.selectReferencedToolNames());
    for (McpTool current : repository.listTools(name)) {
      if (!nextNames.contains(current.name()) && referencedNames.contains(current.name())) {
        throw new AiInUseException(
            RESOURCE,
            "mcp tool "
                + current.name()
                + " is currently referenced by an agent and cannot be removed");
      }
    }
    repository.deleteTools(name);
    for (McpTool tool : discovered) {
      repository.insertTool(tool);
    }
    if (!repository.updateDiscoveryStatus(name, locked.version(), McpDiscoveryStatus.AVAILABLE)) {
      throw new AiVersionConflictException(
          RESOURCE,
          CatalogVersions.format(locked.version()),
          CatalogVersions.format(locked.version()));
    }
  }

  private McpServer insert(String name, HttpConfig config) {
    McpServer server = withConfig(McpServer.builder().name(name).version(0L).build(), config);
    try {
      if (!repository.create(server)) {
        throw new IllegalStateException("Failed to insert MCP server");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(RESOURCE, "mcp server name already exists: " + name, error);
    }
    return server;
  }

  /** 应用规范化后的显式配置并重置发现状态为 UNVERIFIED。 */
  private static McpServer withConfig(McpServer server, HttpConfig config) {
    return server.toBuilder()
        .url(config.url())
        .headers(config.headers())
        .enabled(config.enabled())
        .timeoutMillis(config.timeoutMillis())
        .discoveryStatus(McpDiscoveryStatus.UNVERIFIED)
        .build();
  }

  private McpServer requireServer(String name) {
    McpConfigParser.requireName(name);
    return repository.getByName(name).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private McpServer requireServerForUpdate(String name) {
    return repository
        .getForUpdate(name)
        .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private static void requireVersion(McpServer server, long expectedVersion, String raw) {
    if (server.version() != expectedVersion) {
      throw new AiVersionConflictException(RESOURCE, raw, CatalogVersions.format(server.version()));
    }
  }

  /** Server 行到公开安全视图的投影：绝不回显 URL 与 headers。 */
  private static McpServerDTO toDto(McpServer server, int toolCount) {
    McpServerDTO dto = new McpServerDTO();
    dto.setName(server.name());
    dto.setEnabled(server.enabled());
    dto.setTimeoutMillis(server.timeoutMillis());
    dto.setDiscoveryStatus(server.discoveryStatus().name());
    dto.setToolCount(toolCount);
    dto.setVersion(CatalogVersions.format(server.version()));
    dto.setCreateTime(server.createTime());
    dto.setUpdateTime(server.updateTime());
    return dto;
  }
}
