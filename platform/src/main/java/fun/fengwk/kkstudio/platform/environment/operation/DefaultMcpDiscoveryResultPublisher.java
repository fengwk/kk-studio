package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpToolNameNormalizer;
import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpInputSchemaConverter;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 原子消费 Local MCP 发现结果并持久化工具目录的 {@link McpDiscoveryResultPublisher} 实现。
 *
 * <p>锁顺序固定为 {@code environment} (key share) → {@code environment_connection} (share) → {@code
 * mcp_server} (update) → {@code environment_operation} (terminal update)。在同一事务中严格比对 Server
 * 身份、连接类型、Environment 归属与行版本，原子更新工具目录和 Server 发现状态，最后推进操作终态；终态围栏失效时整单事务回滚。
 */
@Slf4j
@Component
public class DefaultMcpDiscoveryResultPublisher implements McpDiscoveryResultPublisher {

  private static final String CONNECTION_FENCE_SQL =
      """
      select environment_id
      from environment_connection
      where environment_id = ?
        and owner_node_id = ?
        and lease_token = ?
        and status = 'READY'
        and lease_until > statement_timestamp()
      for share
      """;
  private static final Set<String> ENVELOPE_FIELDS = Set.of("serverId", "configVersion", "tools");
  private static final Set<String> TOOL_FIELDS = Set.of("name", "description", "inputSchema");

  private final EnvironmentOperationRepository operationRepository;
  private final McpServerRepository mcpServerRepository;
  private final EnvironmentRepository environmentRepository;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;

  public DefaultMcpDiscoveryResultPublisher(
      EnvironmentOperationRepository operationRepository,
      McpServerRepository mcpServerRepository,
      EnvironmentRepository environmentRepository,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository");
    this.mcpServerRepository = Objects.requireNonNull(mcpServerRepository, "mcpServerRepository");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public OperationPublishOutcome publishDiscoverySuccess(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      EnvironmentCapabilityResult result) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(result, "result");

    String envelopeJson = extractEnvelopeJson(result);
    if (envelopeJson == null) {
      log.warn("MCP discovery result missing JSON payload for operation {}", operationId);
      return publishInvalid(
          environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion);
    }

    JsonNode envelope;
    try {
      envelope = objectMapper.readTree(envelopeJson);
    } catch (JsonProcessingException error) {
      log.warn("MCP discovery returned invalid JSON for operation {}", operationId);
      return publishInvalid(
          environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion);
    }

    if (!envelope.isObject()
        || !hasExactFields(envelope, ENVELOPE_FIELDS)
        || !resourceId.toString().equals(envelope.path("serverId").asText())
        || !envelope.path("configVersion").isIntegralNumber()
        || !envelope.path("configVersion").canConvertToLong()
        || resourceVersion != envelope.path("configVersion").longValue()
        || !envelope.path("tools").isArray()) {
      log.warn("MCP discovery envelope shape or identity mismatch for operation {}", operationId);
      return publishInvalid(
          environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion);
    }

    JsonNode toolsArray = envelope.path("tools");
    Set<String> sourceNames = new HashSet<>();
    for (JsonNode toolNode : toolsArray) {
      String name = toolNode.path("name").asText(null);
      JsonNode descriptionNode = toolNode.get("description");
      if (!toolNode.isObject()
          || !hasExactFields(toolNode, TOOL_FIELDS)
          || name == null
          || name.isBlank()
          || name.length() > 128
          || !sourceNames.add(name)
          || descriptionNode == null
          || !descriptionNode.isTextual()
          || descriptionNode.asText().isBlank()) {
        log.warn("MCP discovery tool has invalid name for operation {}", operationId);
        return publishInvalid(
            environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion);
      }
      JsonNode schemaNode = toolNode.get("inputSchema");
      if (schemaNode == null || !schemaNode.isObject()) {
        log.warn("MCP discovery tool has non-object inputSchema for operation {}", operationId);
        return publishInvalid(
            environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion);
      }
    }

    try {
      return requireTransactionResult(
          transactionTemplate.execute(
              status -> {
                if (!lockReadyConnection(environmentId, ownerNodeId, leaseToken)) {
                  return OperationPublishOutcome.LEASE_LOST;
                }

                Optional<McpServer> serverOpt = mcpServerRepository.getByIdForUpdate(resourceId);
                if (serverOpt.isEmpty()) {
                  return markResourceChanged(operationId, ownerNodeId, leaseToken);
                }

                McpServer server = serverOpt.get();
                if (server.getConnectionType() != McpConnectionType.LOCAL
                    || !Objects.equals(server.getEnvironmentId(), environmentId)
                    || server.getVersion() != resourceVersion) {
                  return markResourceChanged(operationId, ownerNodeId, leaseToken);
                }

                List<McpTool> existingTools = mcpServerRepository.listTools(resourceId);
                Map<String, McpTool> existingBySource = new LinkedHashMap<>();
                for (McpTool existing : existingTools) {
                  existingBySource.put(existing.getSourceName(), existing);
                }

                List<McpToolMutation> mutations =
                    planToolMutations(server, toolsArray, existingTools, existingBySource);
                for (McpToolMutation mutation : mutations) {
                  if (mutation.insert()) {
                    mcpServerRepository.insertTool(mutation.tool());
                  } else {
                    mcpServerRepository.updateTool(mutation.tool());
                  }
                }

                if (!mcpServerRepository.updateDiscoveryResult(
                    resourceId, resourceVersion, McpDiscoveryStatus.AVAILABLE, resourceVersion)) {
                  throw new IllegalStateException(
                      "Server CAS update failed during discovery commit");
                }

                String summary =
                    "{\"toolCount\":" + toolsArray.size() + ",\"status\":\"AVAILABLE\"}";
                if (!operationRepository.markSucceeded(
                    operationId, ownerNodeId, leaseToken, summary)) {
                  throw new OperationLeaseLostException();
                }
                return OperationPublishOutcome.APPLIED;
              }));
    } catch (OperationLeaseLostException leaseLost) {
      log.warn("Lease lost while publishing MCP discovery result for operation {}", operationId);
      return OperationPublishOutcome.LEASE_LOST;
    } catch (InvalidDiscoveryResultException invalidResult) {
      log.warn("MCP discovery returned an invalid tool catalog for operation {}", operationId);
      return publishInvalid(
          environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion);
    }
  }

  @Override
  public OperationPublishOutcome publishDiscoveryFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      String failureCode,
      String failureMessage) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(failureCode, "failureCode");
    Objects.requireNonNull(failureMessage, "failureMessage");

    try {
      return requireTransactionResult(
          transactionTemplate.execute(
              status -> {
                if (!lockReadyConnection(environmentId, ownerNodeId, leaseToken)) {
                  return OperationPublishOutcome.LEASE_LOST;
                }
                Optional<McpServer> serverOpt = mcpServerRepository.getByIdForUpdate(resourceId);
                if (serverOpt.isEmpty()) {
                  return markResourceChanged(operationId, ownerNodeId, leaseToken);
                }
                McpServer server = serverOpt.get();
                if (server.getConnectionType() != McpConnectionType.LOCAL
                    || !Objects.equals(server.getEnvironmentId(), environmentId)
                    || server.getVersion() != resourceVersion) {
                  return markResourceChanged(operationId, ownerNodeId, leaseToken);
                }
                if (!mcpServerRepository.updateDiscoveryResult(
                    resourceId,
                    resourceVersion,
                    McpDiscoveryStatus.FAILED,
                    server.getDiscoveredVersion())) {
                  throw new IllegalStateException(
                      "Server CAS update failed during discovery failure commit");
                }
                if (!operationRepository.markFailed(
                    operationId, ownerNodeId, leaseToken, failureCode, failureMessage)) {
                  throw new OperationLeaseLostException();
                }
                return OperationPublishOutcome.APPLIED;
              }));
    } catch (OperationLeaseLostException leaseLost) {
      log.warn("Lease lost while publishing MCP discovery failure for operation {}", operationId);
      return OperationPublishOutcome.LEASE_LOST;
    }
  }

  private OperationPublishOutcome publishInvalid(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion) {
    return publishDiscoveryFailure(
        environmentId,
        operationId,
        ownerNodeId,
        leaseToken,
        resourceId,
        resourceVersion,
        EnvironmentOperationFailureCodes.INVALID_RESULT,
        EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
  }

  private boolean lockReadyConnection(UUID environmentId, UUID ownerNodeId, UUID leaseToken) {
    if (environmentRepository.lockForKeyShare(environmentId) == null) {
      return false;
    }
    return !jdbcTemplate
        .query(
            CONNECTION_FENCE_SQL,
            (rs, rowNum) -> rs.getObject("environment_id", UUID.class),
            environmentId,
            ownerNodeId,
            leaseToken)
        .isEmpty();
  }

  private OperationPublishOutcome markResourceChanged(
      UUID operationId, UUID ownerNodeId, UUID leaseToken) {
    if (!operationRepository.markFailed(
        operationId,
        ownerNodeId,
        leaseToken,
        EnvironmentOperationFailureCodes.RESOURCE_CHANGED,
        EnvironmentOperationFailureCodes.RESOURCE_CHANGED_MESSAGE)) {
      throw new OperationLeaseLostException();
    }
    return OperationPublishOutcome.RESOURCE_CHANGED;
  }

  private List<McpToolMutation> planToolMutations(
      McpServer server,
      JsonNode toolsArray,
      List<McpTool> existingTools,
      Map<String, McpTool> existingBySource) {
    List<McpToolMutation> mutations = new ArrayList<>();
    Set<String> discoveredSourceNames = new HashSet<>();
    Set<String> newModelNames = new HashSet<>();
    for (JsonNode toolNode : toolsArray) {
      String sourceName = toolNode.path("name").asText();
      discoveredSourceNames.add(sourceName);
      String description = toolNode.path("description").asText().strip();
      String inputSchemaJson;
      try {
        inputSchemaJson = McpInputSchemaConverter.toCanonicalJson(toolNode.get("inputSchema"));
      } catch (RuntimeException error) {
        throw new InvalidDiscoveryResultException();
      }

      McpTool existing = existingBySource.get(sourceName);
      if (existing != null) {
        McpTool updated = copyTool(existing);
        boolean contentChanged =
            !Objects.equals(existing.getDescription(), description)
                || !isJsonEquals(existing.getInputSchemaJson(), inputSchemaJson);
        if (contentChanged || !existing.isAvailable()) {
          updated.setSchemaRevision(existing.getSchemaRevision() + 1);
        }
        updated.setDescription(description);
        updated.setInputSchemaJson(inputSchemaJson);
        updated.setAvailable(true);
        mutations.add(new McpToolMutation(updated, false));
        continue;
      }

      String modelName;
      try {
        modelName = McpToolNameNormalizer.requireModelToolName(server.getName(), sourceName);
      } catch (IllegalArgumentException error) {
        throw new InvalidDiscoveryResultException();
      }
      if (!newModelNames.add(modelName) || mcpServerRepository.isModelNameTaken(modelName, null)) {
        throw new InvalidDiscoveryResultException();
      }
      McpTool inserted = new McpTool();
      inserted.setId(UUID.randomUUID());
      inserted.setServerId(server.getId());
      inserted.setSourceName(sourceName);
      inserted.setModelName(modelName);
      inserted.setDescription(description);
      inserted.setInputSchemaJson(inputSchemaJson);
      inserted.setSchemaRevision(0L);
      inserted.setAvailable(true);
      mutations.add(new McpToolMutation(inserted, true));
    }

    for (McpTool existing : existingTools) {
      if (!discoveredSourceNames.contains(existing.getSourceName()) && existing.isAvailable()) {
        McpTool tombstone = copyTool(existing);
        tombstone.setAvailable(false);
        mutations.add(new McpToolMutation(tombstone, false));
      }
    }
    return List.copyOf(mutations);
  }

  private static McpTool copyTool(McpTool source) {
    McpTool copy = new McpTool();
    copy.setId(source.getId());
    copy.setServerId(source.getServerId());
    copy.setSourceName(source.getSourceName());
    copy.setModelName(source.getModelName());
    copy.setDescription(source.getDescription());
    copy.setInputSchemaJson(source.getInputSchemaJson());
    copy.setSchemaRevision(source.getSchemaRevision());
    copy.setAvailable(source.isAvailable());
    return copy;
  }

  private static boolean hasExactFields(JsonNode node, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    return actual.equals(expected);
  }

  private static OperationPublishOutcome requireTransactionResult(OperationPublishOutcome outcome) {
    return Objects.requireNonNull(outcome, "transaction result");
  }

  private String extractEnvelopeJson(EnvironmentCapabilityResult result) {
    if (result.contents() == null) {
      return null;
    }
    for (ResultContent content : result.contents()) {
      if (content instanceof JsonResultContent jsonContent) {
        return jsonContent.json();
      }
    }
    return null;
  }

  private boolean isJsonEquals(String json1, String json2) {
    if (Objects.equals(json1, json2)) {
      return true;
    }
    if (json1 == null || json2 == null) {
      return false;
    }
    try {
      return objectMapper.readTree(json1).equals(objectMapper.readTree(json2));
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  private record McpToolMutation(McpTool tool, boolean insert) {}

  private static final class OperationLeaseLostException extends RuntimeException {}

  private static final class InvalidDiscoveryResultException extends RuntimeException {}
}
