package fun.fengwk.kkstudio.platform.catalog.mcp.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerConverter;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator.HttpConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator.NormalizedCreate;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator.NormalizedUpdate;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Platform MCP server 应用服务实现：name-keyed CRUD/CAS 与事务外发现 + 事务内原子替换。 */
@Service
public class McpServerServiceImpl implements McpServerService {

  private static final String RESOURCE = "mcp_server";

  private final McpServerRepository repository;
  private final McpToolDiscovery toolDiscovery;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  public McpServerServiceImpl(
      McpServerRepository repository, PlatformTransactionManager transactionManager) {
    this(repository, new McpToolDiscovery(), transactionManager);
  }

  public McpServerServiceImpl(
      McpServerRepository repository,
      McpToolDiscovery toolDiscovery,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.toolDiscovery = Objects.requireNonNull(toolDiscovery, "toolDiscovery");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  @Override
  public Page<McpServerDTO> pageServers(PageQuery pageQuery) {
    return repository
        .page(pageQuery)
        .map(server -> McpServerConverter.convert(server, repository.countTools(server.getName())));
  }

  @Override
  public McpServerDTO getServer(String name) {
    McpServer server = requireServer(name);
    return McpServerConverter.convert(server, repository.countTools(name));
  }

  @Override
  public McpServerConfigDTO getServerConfig(String name) {
    McpServer server = requireServer(name);
    McpServerConfigDTO configDTO = new McpServerConfigDTO();
    configDTO.setName(server.getName());
    configDTO.setVersion(CatalogVersions.format(server.getVersion()));
    configDTO.setUrl(server.getUrl());
    configDTO.setHeaders(server.getHeaders());
    return configDTO;
  }

  @Override
  public McpServerDTO createServer(McpServerCreateDTO createDTO) {
    NormalizedCreate normalized = McpServerMutationValidator.normalizeCreate(createDTO);
    if (repository.getByName(normalized.name()).isPresent()) {
      throw new AiDuplicateException(
          RESOURCE, "mcp server name already exists: " + normalized.name());
    }

    McpServer server = new McpServer();
    server.setName(normalized.name());
    applyConfig(server, normalized.config());
    server.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);
    server.setVersion(0L);

    try {
      if (!repository.create(server)) {
        throw new IllegalStateException("Failed to insert MCP server");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "mcp server name already exists: " + normalized.name(), error);
    }
    return McpServerConverter.convert(server, 0);
  }

  @Override
  public McpServerDTO updateServer(String name, McpServerUpdateDTO updateDTO) {
    McpServerMutationValidator.requireName(name);
    if (updateDTO == null || updateDTO.getExpectedVersion() == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion must not be null");
    }
    long expectedVersion = CatalogVersions.parse(updateDTO.getExpectedVersion(), "expectedVersion");
    NormalizedUpdate normalized = McpServerMutationValidator.normalizeUpdate(updateDTO);

    McpServer updatedServer =
        transactionTemplate.execute(
            status -> {
              McpServer locked =
                  repository
                      .getForUpdate(name)
                      .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
              requireVersion(locked, expectedVersion, updateDTO.getExpectedVersion());

              applyConfig(locked, normalized.config());
              locked.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);

              if (!repository.update(locked, expectedVersion)) {
                throw new AiVersionConflictException(
                    RESOURCE,
                    updateDTO.getExpectedVersion(),
                    CatalogVersions.format(locked.getVersion()));
              }
              locked.setVersion(expectedVersion + 1);
              return locked;
            });

    return McpServerConverter.convert(updatedServer, repository.countTools(name));
  }

  @Override
  public McpServerDTO discoverServer(String name, String rawExpectedVersion) {
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");
    McpServer server = requireServer(name);
    requireVersion(server, expectedVersion, rawExpectedVersion);

    // 网络与工具名校验全部发生在事务之外：失败绝不触碰既有工具行。
    List<McpTool> discovered = toolDiscovery.discover(server);

    McpServer appliedServer =
        transactionTemplate.execute(
            status -> {
              McpServer locked =
                  repository
                      .getForUpdate(name)
                      .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
              requireVersion(locked, expectedVersion, rawExpectedVersion);

              // 被 Agent 引用的工具名若将从目录中消失，则 fail closed 并完整保留旧快照。
              Set<String> currentNames =
                  repository.listTools(name).stream()
                      .map(McpTool::getName)
                      .collect(Collectors.toSet());
              Set<String> nextNames =
                  discovered.stream().map(McpTool::getName).collect(Collectors.toSet());
              Set<String> referencedNames = new HashSet<>(repository.selectReferencedToolNames());
              for (String currentName : currentNames) {
                if (!nextNames.contains(currentName) && referencedNames.contains(currentName)) {
                  throw new AiInUseException(
                      RESOURCE,
                      "mcp tool "
                          + currentName
                          + " is currently referenced by an agent and cannot be removed");
                }
              }

              // 成功发现整体物理替换当前结果：先清空再写入，不存在 tombstone 或修订版本。
              repository.deleteTools(name);
              for (McpTool tool : discovered) {
                repository.insertTool(tool);
              }
              if (!repository.updateDiscoveryStatus(
                  name, expectedVersion, McpDiscoveryStatus.AVAILABLE)) {
                throw new AiVersionConflictException(
                    RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.getVersion()));
              }
              locked.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
              return locked;
            });

    return McpServerConverter.convert(appliedServer, discovered.size());
  }

  @Override
  public void deleteServer(String name, String rawExpectedVersion) {
    McpServerMutationValidator.requireName(name);
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");

    transactionTemplate.execute(
        status -> {
          McpServer locked =
              repository
                  .getForUpdate(name)
                  .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
          requireVersion(locked, expectedVersion, rawExpectedVersion);

          List<String> referenced = repository.selectReferencedToolNames();
          for (McpTool tool : repository.listTools(name)) {
            if (referenced.contains(tool.getName())) {
              throw new AiInUseException(
                  RESOURCE, "mcp tool " + tool.getName() + " is currently referenced by an agent");
            }
          }

          if (!repository.delete(name, expectedVersion)) {
            throw new AiVersionConflictException(
                RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.getVersion()));
          }
          return null;
        });
  }

  private static void applyConfig(McpServer server, HttpConfig config) {
    server.setUrl(config.url());
    server.setHeaders(config.headers());
    server.setEnabled(config.enabled());
    server.setTimeoutMillis(config.timeoutMillis());
  }

  private McpServer requireServer(String name) {
    McpServerMutationValidator.requireName(name);
    return repository.getByName(name).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private static void requireVersion(McpServer server, long expectedVersion, String raw) {
    if (server.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          RESOURCE, raw, CatalogVersions.format(server.getVersion()));
    }
  }
}
