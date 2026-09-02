package fun.fengwk.kkstudio.platform.catalog.mcp.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerConverter;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Platform MCP server 应用服务实现：严格事务边界的保存流程。
 *
 * <p>update/refresh：事务外读取当前 Server/既有工具快照 → per-call 发现 + 完整校验（含 model_name 冲突）→ 开启 DB 事务 → {@code
 * SELECT ... FOR UPDATE} server 行 → 重查 version 与事务外快照一致 → CAS update + 原子 upsert/delete
 * 工具行。连接、发现、名称/schema 冲突失败时 DB 完全不变。create 同样先发现后事务插入。 parent 删除与 agent 引用检查使用同一 server 行锁。
 */
@Service
public class McpServerServiceImpl implements McpServerService {

  private static final String RESOURCE = "mcp_server";

  private final McpServerRepository repository;
  private final McpToolClientFactory clientFactory;
  private final TransactionTemplate transactionTemplate;

  public McpServerServiceImpl(
      McpServerRepository repository,
      McpToolClientFactory clientFactory,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  @Override
  public Page<McpServerDTO> pageServers(PageQuery pageQuery) {
    return repository.page(pageQuery).map(McpServerConverter::convert);
  }

  @Override
  public McpServerDTO getServer(String id) {
    return McpServerConverter.convert(requireServer(parseId(id)));
  }

  @Override
  public McpServerDTO createServer(McpServerCreateDTO createDTO) {
    McpServerMutationValidator.NormalizedCreate normalized =
        McpServerMutationValidator.normalizeCreate(createDTO);
    ensureNameAvailable(normalized.name());

    // 事务外完整发现：连接、握手、tools/list、全部校验通过才进入写路径。
    McpServer server = newServerSnapshot(normalized);
    List<McpTool> candidates = discoverCandidates(server, List.of(), null);
    try {
      boolean created =
          transactionTemplate.execute(
              status -> {
                if (!repository.create(server)) {
                  throw new IllegalStateException("create mcp server failed");
                }
                for (McpTool candidate : candidates) {
                  repository.insertTool(candidate);
                }
                return true;
              });
      if (!Boolean.TRUE.equals(created)) {
        throw new IllegalStateException("create mcp server failed");
      }
    } catch (DuplicateKeyException error) {
      // name 唯一索引竞态兜底；事务已回滚，DB 不变。
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists: " + normalized.name(), error);
    }
    return McpServerConverter.convert(requireServer(server.getId()));
  }

  @Override
  public McpServerDTO updateServer(String id, McpServerUpdateDTO updateDTO) {
    UUID serverId = parseId(id);
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    McpServerMutationValidator.NormalizedUpdate normalized =
        McpServerMutationValidator.normalizeUpdate(updateDTO);

    // 事务外快照：当前行 + 既有工具 + 合并新配置后的完整发现。
    McpServer current = requireServer(serverId);
    ensureExpectedVersion(current, serverId, rawExpected, expected);
    List<McpTool> existingTools = repository.listTools(serverId);
    McpServer merged = mergeServer(current, normalized);
    McpToolDiscovery discovery = discovery();
    McpToolDiscovery.DiscoveryResult result =
        discovery.discover(merged, existingTools, repository, null);

    return transactionTemplate.execute(
        status -> {
          McpServer locked = requireServerForUpdate(serverId);
          // 重查版本：与事务外快照不一致时 CAS 必然失败，提前给出确定性冲突。
          ensureExpectedVersion(locked, serverId, rawExpected, expected);
          applyUpdate(locked, normalized);
          if (!repository.updateById(locked, expected)) {
            McpServer reread = requireServer(serverId);
            throw new AiVersionConflictException(
                RESOURCE,
                serverId.toString(),
                rawExpected,
                CatalogVersions.format(reread.getVersion()));
          }
          applyToolUpserts(serverId, result);
          return McpServerConverter.convert(requireServer(serverId));
        });
  }

  @Override
  public McpServerDTO refreshServer(String id, String expectedVersion) {
    UUID serverId = parseId(id);
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");

    McpServer current = requireServer(serverId);
    ensureExpectedVersion(current, serverId, expectedVersion, expected);
    List<McpTool> existingTools = repository.listTools(serverId);
    McpToolDiscovery.DiscoveryResult result =
        discovery().discover(current, existingTools, repository, null);

    return transactionTemplate.execute(
        status -> {
          McpServer locked = requireServerForUpdate(serverId);
          ensureExpectedVersion(locked, serverId, expectedVersion, expected);
          if (!repository.updateById(locked, expected)) {
            McpServer reread = requireServer(serverId);
            throw new AiVersionConflictException(
                RESOURCE,
                serverId.toString(),
                expectedVersion,
                CatalogVersions.format(reread.getVersion()));
          }
          applyToolUpserts(serverId, result);
          return McpServerConverter.convert(requireServer(serverId));
        });
  }

  @Override
  public void deleteServer(String id, String expectedVersion) {
    UUID serverId = parseId(id);
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    transactionTemplate.executeWithoutResult(
        status -> {
          // parent 行锁同时串行化引用检查与删除，防止引用竞态。
          McpServer locked =
              repository.getByIdForUpdate(serverId).orElseThrow(() -> notFound(serverId));
          ensureExpectedVersion(locked, serverId, expectedVersion, expected);
          ensureNotReferenced(locked);
          if (!repository.deleteById(serverId, expected)) {
            McpServer reread = repository.getById(serverId).orElseThrow(() -> notFound(serverId));
            throw new AiVersionConflictException(
                RESOURCE,
                serverId.toString(),
                expectedVersion,
                CatalogVersions.format(reread.getVersion()));
          }
        });
  }

  private void applyToolUpserts(UUID serverId, McpToolDiscovery.DiscoveryResult result) {
    Set<String> discoveredSources = new HashSet<>();
    for (McpTool candidate : result.candidates()) {
      discoveredSources.add(candidate.getSourceName());
      if (result.existingBySource().containsKey(candidate.getSourceName())) {
        // 既有 (serverId, sourceName)：保留 UUID/model_name，仅刷新 description/schema。
        repository.updateTool(candidate);
      } else {
        repository.insertTool(candidate);
      }
    }
    Set<String> referenced = null;
    for (McpTool existing : result.existingBySource().values()) {
      if (!discoveredSources.contains(existing.getSourceName())) {
        if (referenced == null) {
          referenced = new HashSet<>(repository.selectReferencedAgentToolIds());
        }
        String agentToolId = McpStableIds.agentToolId(existing.getId()).value();
        if (referenced.contains(agentToolId)) {
          throw new AiInUseException(
              RESOURCE,
              RESOURCE
                  + " removed tool is referenced by an agent toolIds allowlist: "
                  + agentToolId
                  + " (tool "
                  + existing.getSourceName()
                  + ")");
        }
        // 远端已移除的未引用工具：稳定身份随之消失，直接硬删除。
        repository.deleteTool(serverId, existing.getSourceName());
      }
    }
  }

  private void applyUpdate(McpServer server, McpServerMutationValidator.NormalizedUpdate update) {
    if (update.url() != null) {
      server.setUrl(update.url());
    }
    if (update.bearerToken() != null) {
      server.setBearerToken(update.bearerToken().isEmpty() ? null : update.bearerToken());
    }
    if (update.timeoutMillis() != null) {
      server.setTimeoutMillis(update.timeoutMillis());
    }
  }

  private McpServer mergeServer(
      McpServer current, McpServerMutationValidator.NormalizedUpdate update) {
    McpServer merged = new McpServer();
    merged.setId(current.getId());
    merged.setName(current.getName());
    merged.setUrl(update.url() == null ? current.getUrl() : update.url());
    if (update.bearerToken() != null) {
      merged.setBearerToken(update.bearerToken().isEmpty() ? null : update.bearerToken());
    } else {
      merged.setBearerToken(current.getBearerToken());
    }
    merged.setTimeoutMillis(
        update.timeoutMillis() == null ? current.getTimeoutMillis() : update.timeoutMillis());
    return merged;
  }

  private List<McpTool> discoverCandidates(
      McpServer server, List<McpTool> existingTools, UUID excludeToolId) {
    return discovery().discover(server, existingTools, repository, excludeToolId).candidates();
  }

  private McpToolDiscovery discovery() {
    return McpToolDiscovery.withFactory(clientFactory);
  }

  private void ensureNotReferenced(McpServer locked) {
    Set<String> referenced = new HashSet<>(repository.selectReferencedAgentToolIds());
    for (McpTool tool : repository.listTools(locked.getId())) {
      String agentToolId = McpStableIds.agentToolId(tool.getId()).value();
      if (referenced.contains(agentToolId)) {
        throw new AiInUseException(
            RESOURCE,
            RESOURCE
                + " tool is referenced by an agent toolIds allowlist: "
                + agentToolId
                + " (server "
                + locked.getName()
                + ")");
      }
    }
  }

  private void ensureNameAvailable(String name) {
    if (repository.getByName(name).isPresent()) {
      throw new AiDuplicateException(RESOURCE, RESOURCE + " name already exists: " + name);
    }
  }

  private McpServer newServerSnapshot(McpServerMutationValidator.NormalizedCreate normalized) {
    McpServer server = new McpServer();
    server.setId(UUID.randomUUID());
    server.setName(normalized.name());
    server.setUrl(normalized.url());
    server.setBearerToken(normalized.bearerToken());
    server.setTimeoutMillis(normalized.timeoutMillis());
    return server;
  }

  private McpServer requireServer(UUID id) {
    return repository.getById(id).orElseThrow(() -> notFound(id));
  }

  private McpServer requireServerForUpdate(UUID id) {
    return repository.getByIdForUpdate(id).orElseThrow(() -> notFound(id));
  }

  private static AiResourceNotFoundException notFound(UUID id) {
    return new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
  }

  private static void ensureExpectedVersion(
      McpServer server, UUID id, String rawExpected, long expected) {
    if (server.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, id.toString(), rawExpected, CatalogVersions.format(server.getVersion()));
    }
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(Objects.requireNonNull(id, "id"));
    } catch (RuntimeException error) {
      throw new AiValidationException(RESOURCE, RESOURCE + " id must be a canonical UUID: " + id);
    }
  }
}
