package fun.fengwk.kkstudio.platform.catalog.mcp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery.DiscoveryResult;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerConverter;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator.NormalizedCreate;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator.NormalizedUpdate;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationResourceType;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationService;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationType;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDiscoveryResponseDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Platform MCP server 应用服务实现。 */
@Service
public class McpServerServiceImpl implements McpServerService {

  private static final String RESOURCE = "mcp_server";

  private final McpServerRepository repository;
  private final EnvironmentOperationService environmentOperationService;
  private final McpToolDiscovery toolDiscovery;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;

  @Autowired
  public McpServerServiceImpl(
      McpServerRepository repository,
      EnvironmentOperationService environmentOperationService,
      PlatformTransactionManager transactionManager) {
    this(
        repository,
        environmentOperationService,
        new McpToolDiscovery(),
        transactionManager,
        new ObjectMapper());
  }

  public McpServerServiceImpl(
      McpServerRepository repository,
      EnvironmentOperationService environmentOperationService,
      McpToolDiscovery toolDiscovery,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.environmentOperationService =
        Objects.requireNonNull(environmentOperationService, "environmentOperationService");
    this.toolDiscovery = Objects.requireNonNull(toolDiscovery, "toolDiscovery");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public Page<McpServerDTO> pageServers(PageQuery pageQuery) {
    return repository
        .page(pageQuery)
        .map(
            server -> {
              int toolCount = repository.countAvailableTools(server.getId());
              return McpServerConverter.convert(server, toolCount);
            });
  }

  @Override
  public McpServerDTO getServer(String id) {
    UUID serverId = McpStableIds.requireCanonicalDashedUuid(id, "id");
    McpServer server = requireServer(serverId);
    int toolCount = repository.countAvailableTools(serverId);
    return McpServerConverter.convert(server, toolCount);
  }

  @Override
  public McpServerConfigDTO getServerConfig(String id) {
    UUID serverId = McpStableIds.requireCanonicalDashedUuid(id, "id");
    McpServer server = requireServer(serverId);
    String fullConfigJson = McpConfigParser.toFullConfigJson(server);

    McpServerConfigDTO configDTO = new McpServerConfigDTO();
    configDTO.setId(server.getId().toString());
    configDTO.setName(server.getName());
    configDTO.setVersion(CatalogVersions.format(server.getVersion()));
    configDTO.setConfigJson(fullConfigJson);
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
    server.setId(UUID.randomUUID());
    server.setName(normalized.name());
    server.setConnectionType(normalized.config().connectionType());
    server.setEnvironmentId(normalized.config().environmentId());
    server.setConnectionConfig(normalized.config().connectionConfigJson());
    server.setEnabled(normalized.config().enabled());
    server.setTimeoutMillis(normalized.config().timeoutMillis());
    server.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);
    server.setDiscoveredVersion(null);
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
  public McpServerDTO updateServer(String id, McpServerUpdateDTO updateDTO) {
    UUID serverId = McpStableIds.requireCanonicalDashedUuid(id, "id");
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
                      .getByIdForUpdate(serverId)
                      .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
              if (locked.getVersion() != expectedVersion) {
                throw new AiVersionConflictException(
                    RESOURCE,
                    updateDTO.getExpectedVersion(),
                    CatalogVersions.format(locked.getVersion()));
              }

              locked.setConnectionType(normalized.config().connectionType());
              locked.setEnvironmentId(normalized.config().environmentId());
              locked.setConnectionConfig(normalized.config().connectionConfigJson());
              locked.setEnabled(normalized.config().enabled());
              locked.setTimeoutMillis(normalized.config().timeoutMillis());
              locked.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);
              locked.setDiscoveredVersion(null);

              boolean updated = repository.updateById(locked, expectedVersion);
              if (!updated) {
                throw new AiVersionConflictException(
                    RESOURCE,
                    updateDTO.getExpectedVersion(),
                    CatalogVersions.format(locked.getVersion()));
              }
              locked.setVersion(expectedVersion + 1);
              return locked;
            });

    int toolCount = repository.countAvailableTools(serverId);
    return McpServerConverter.convert(updatedServer, toolCount);
  }

  @Override
  public McpServerDiscoveryResponseDTO discoverServer(String id, String rawExpectedVersion) {
    UUID serverId = McpStableIds.requireCanonicalDashedUuid(id, "id");
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");
    McpServer server = requireServer(serverId);
    if (server.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          RESOURCE, rawExpectedVersion, CatalogVersions.format(server.getVersion()));
    }

    if (server.getConnectionType() == McpConnectionType.REMOTE) {
      List<McpTool> existingTools = repository.listTools(serverId);
      DiscoveryResult discoveryResult;
      try {
        discoveryResult = toolDiscovery.discover(server, existingTools, repository, null);
      } catch (Exception error) {
        transactionTemplate.execute(
            status -> {
              Optional<McpServer> cur = repository.getByIdForUpdate(serverId);
              if (cur.isPresent() && cur.get().getVersion() == expectedVersion) {
                repository.updateDiscoveryResult(
                    serverId,
                    expectedVersion,
                    McpDiscoveryStatus.FAILED,
                    server.getDiscoveredVersion());
              }
              return null;
            });
        throw error;
      }

      McpServer appliedServer =
          transactionTemplate.execute(
              status -> {
                McpServer locked =
                    repository
                        .getByIdForUpdate(serverId)
                        .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
                if (locked.getVersion() != expectedVersion) {
                  throw new AiVersionConflictException(
                      RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.getVersion()));
                }

                for (McpTool candidate : discoveryResult.candidates()) {
                  if (existingTools.stream()
                      .anyMatch(t -> t.getSourceName().equals(candidate.getSourceName()))) {
                    repository.updateTool(candidate);
                  } else {
                    repository.insertTool(candidate);
                  }
                }
                for (McpTool tombstone : discoveryResult.tombstones()) {
                  repository.updateTool(tombstone);
                }

                repository.updateDiscoveryResult(
                    serverId, expectedVersion, McpDiscoveryStatus.AVAILABLE, expectedVersion);
                locked.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
                locked.setDiscoveredVersion(expectedVersion);
                return locked;
              });

      int toolCount = repository.countAvailableTools(serverId);
      return new McpServerDiscoveryResponseDTO(
          McpServerConverter.convert(appliedServer, toolCount), null);
    } else {
      String fullLocalConfigJson = McpConfigParser.toFullConfigJson(server);
      ObjectNode opArgsNode = objectMapper.createObjectNode();
      opArgsNode.put("serverId", serverId.toString());
      opArgsNode.put("configVersion", expectedVersion);
      try {
        opArgsNode.set("config", objectMapper.readTree(fullLocalConfigJson));
      } catch (JsonProcessingException error) {
        throw new IllegalStateException("Failed to encode local config json", error);
      }
      String opArgsJson = opArgsNode.toString();
      String paramSummary = "{\"serverName\":\"" + server.getName() + "\",\"type\":\"local\"}";

      EnvironmentOperationDTO operationDTO =
          environmentOperationService.createOperation(
              EnvironmentId.of(server.getEnvironmentId()),
              EnvironmentOperationType.MCP_SERVER_DISCOVER,
              EnvironmentOperationResourceType.MCP_SERVER,
              serverId,
              expectedVersion,
              opArgsJson,
              paramSummary,
              server.getTimeoutMillis());

      int toolCount = repository.countAvailableTools(serverId);
      return new McpServerDiscoveryResponseDTO(
          McpServerConverter.convert(server, toolCount), operationDTO);
    }
  }

  @Override
  public void deleteServer(String id, String rawExpectedVersion) {
    UUID serverId = McpStableIds.requireCanonicalDashedUuid(id, "id");
    long expectedVersion = CatalogVersions.parse(rawExpectedVersion, "expectedVersion");

    transactionTemplate.execute(
        status -> {
          McpServer locked =
              repository
                  .getByIdForUpdate(serverId)
                  .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
          if (locked.getVersion() != expectedVersion) {
            throw new AiVersionConflictException(
                RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.getVersion()));
          }

          List<String> referenced = repository.selectReferencedAgentToolIds();
          List<McpTool> tools = repository.listTools(serverId);
          for (McpTool tool : tools) {
            String agentToolId = McpStableIds.agentToolId(tool.getId()).value();
            if (referenced.contains(agentToolId)) {
              throw new AiInUseException(
                  RESOURCE, "mcp tool " + agentToolId + " is currently referenced by an agent");
            }
          }

          if (!repository.deleteById(serverId, expectedVersion)) {
            throw new AiVersionConflictException(
                RESOURCE, rawExpectedVersion, CatalogVersions.format(locked.getVersion()));
          }
          return null;
        });
  }

  private McpServer requireServer(UUID id) {
    return repository.getById(id).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }
}
