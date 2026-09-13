package fun.fengwk.kkstudio.platform.catalog.mcp.discovery;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;
import fun.fengwk.kkstudio.harness.mcp.RemoteMcpConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpToolNameNormalizer;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.RemoteConnectionConfig;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Remote MCP 工具发现与校验服务。
 *
 * <p>以 per-call McpClient 发起 tools/list，并按规则校验工具名、描述、JSON input schema。 错误信息绝不回显 URL、敏感 header
 * 或异常原因。
 */
public final class McpToolDiscovery {

  private static final String RESOURCE = "mcp_server";
  public static final int SOURCE_NAME_MAX_LENGTH = 128;

  private final BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider;
  private final Function<String, String> envProvider;

  public McpToolDiscovery() {
    this(McpClientFactory::createRemote, System::getenv);
  }

  public McpToolDiscovery(
      BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider,
      Function<String, String> envProvider) {
    this.clientProvider = Objects.requireNonNull(clientProvider, "clientProvider");
    this.envProvider = Objects.requireNonNull(envProvider, "envProvider");
  }

  /** 执行远端发现并返回候选工具列表与 tombstone 列表；不直接持久化。 */
  public DiscoveryResult discover(
      McpServer server,
      List<McpTool> existingTools,
      McpServerRepository repository,
      UUID excludeToolId) {
    Objects.requireNonNull(server, "server");
    List<McpToolDefinition> remoteTools = listRemoteTools(server);

    Map<String, McpTool> existingBySource = new LinkedHashMap<>();
    for (McpTool existing : existingTools) {
      existingBySource.put(existing.getSourceName(), existing);
    }

    Set<String> seenSourceNames = new HashSet<>();
    Set<String> seenModelNames = new HashSet<>();
    List<McpTool> candidates = new ArrayList<>(remoteTools.size());

    for (McpToolDefinition remote : remoteTools) {
      String sourceName = validateSourceName(remote.name());
      if (!seenSourceNames.add(sourceName)) {
        throw new AiValidationException(
            RESOURCE, "mcp server returned duplicate tools for source name: " + sourceName);
      }
      String description = requireDescription(remote.description(), sourceName);
      String inputSchemaJson = canonicalizeSchema(remote.inputSchemaJson(), sourceName);
      String modelName = requireModelName(server, sourceName);
      if (!seenModelNames.add(modelName)) {
        throw normalizedModelNameConflict(server, modelName);
      }

      McpTool candidate = new McpTool();
      candidate.setServerId(server.getId());
      candidate.setSourceName(sourceName);
      candidate.setDescription(description);
      candidate.setInputSchemaJson(inputSchemaJson);
      candidate.setAvailable(true);

      McpTool existing = existingBySource.get(sourceName);
      if (existing != null) {
        candidate.setId(existing.getId());
        candidate.setModelName(existing.getModelName());
        boolean changed =
            !Objects.equals(existing.getDescription(), description)
                || !Objects.equals(existing.getInputSchemaJson(), inputSchemaJson);
        boolean reappeared = !existing.isAvailable();
        long revision = existing.getSchemaRevision();
        if (changed || reappeared) {
          revision++;
        }
        candidate.setSchemaRevision(revision);
      } else {
        candidate.setId(UUID.randomUUID());
        candidate.setModelName(modelName);
        candidate.setSchemaRevision(0L);
      }
      candidates.add(candidate);
    }

    if (repository != null) {
      for (McpTool candidate : candidates) {
        UUID excluded =
            existingBySource.containsKey(candidate.getSourceName())
                ? candidate.getId()
                : excludeToolId;
        if (repository.isModelNameTaken(candidate.getModelName(), excluded)) {
          throw new AiValidationException(
              RESOURCE,
              "mcp tool model name conflicts with an existing tool: " + candidate.getModelName());
        }
      }
    }

    List<McpTool> tombstones = new ArrayList<>();
    for (McpTool existing : existingTools) {
      if (!seenSourceNames.contains(existing.getSourceName()) && existing.isAvailable()) {
        existing.setAvailable(false);
        tombstones.add(existing);
      }
    }

    return new DiscoveryResult(List.copyOf(candidates), List.copyOf(tombstones));
  }

  private List<McpToolDefinition> listRemoteTools(McpServer server) {
    RemoteConnectionConfig remoteConfig =
        (RemoteConnectionConfig)
            McpConfigParser.parseConnectionConfig(
                server.getConnectionType(), server.getConnectionConfig());
    Map<String, String> resolvedHeaders =
        McpConfigParser.resolveRemoteHeaders(remoteConfig.headers(), envProvider);
    RemoteMcpConfig config = new RemoteMcpConfig(remoteConfig.url(), resolvedHeaders);
    Duration timeout = Duration.ofMillis(server.getTimeoutMillis());
    McpDeadline deadline = McpDeadline.of(timeout);
    McpCancellationToken token = McpCancellationToken.none();

    try (McpClient client = clientProvider.apply(config, deadline)) {
      List<McpToolDefinition> tools = client.listTools(deadline, token);
      return tools == null ? List.of() : tools;
    } catch (RuntimeException error) {
      throw new AiValidationException(RESOURCE, "MCP server discovery failed.");
    }
  }

  private static String validateSourceName(String rawName) {
    if (rawName == null || rawName.isBlank()) {
      throw new AiValidationException(RESOURCE, "mcp tool source name must not be blank");
    }
    if (rawName.length() > SOURCE_NAME_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE,
          "mcp tool source name exceeds " + SOURCE_NAME_MAX_LENGTH + " characters: " + rawName);
    }
    return rawName;
  }

  private static String requireDescription(String description, String sourceName) {
    if (description == null || description.isBlank()) {
      throw new AiValidationException(
          RESOURCE, "mcp tool description must not be blank: " + sourceName);
    }
    return description.strip();
  }

  private static String canonicalizeSchema(String inputSchemaJson, String sourceName) {
    try {
      InputSchema schema = McpInputSchemaConverter.toCanonicalSchema(inputSchemaJson, RESOURCE);
      return new SchemaJsonCodec().encode(schema);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, "mcp tool input schema is invalid: " + sourceName);
    }
  }

  private static String requireModelName(McpServer server, String sourceName) {
    try {
      return McpToolNameNormalizer.requireModelToolName(server.getName(), sourceName);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage());
    }
  }

  private static AiValidationException normalizedModelNameConflict(
      McpServer server, String modelName) {
    return new AiValidationException(
        RESOURCE,
        "mcp tools normalize to duplicate model names: "
            + modelName
            + " (server "
            + server.getName()
            + ")");
  }

  /** 发现结果载体。 */
  public record DiscoveryResult(List<McpTool> candidates, List<McpTool> tombstones) {}
}
