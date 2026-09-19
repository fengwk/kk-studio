package fun.fengwk.kkstudio.platform.catalog.mcp.discovery;

import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;
import fun.fengwk.kkstudio.harness.mcp.RemoteMcpConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpToolNameNormalizer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Streamable HTTP MCP 工具发现与校验服务。
 *
 * <p>以 per-call McpClient 发起 tools/list，并按规则校验工具名、描述、JSON input schema。错误信息绝不回显 URL、敏感 header
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

  /** 执行远端发现并返回该 server 的完整候选工具行；不直接持久化。 */
  public List<McpTool> discover(McpServer server) {
    Objects.requireNonNull(server, "server");
    List<McpToolDefinition> remoteTools = listRemoteTools(server);

    Set<String> seenSourceNames = new HashSet<>();
    Set<String> seenToolNames = new HashSet<>();
    List<McpTool> candidates = new ArrayList<>(remoteTools.size());

    for (McpToolDefinition remote : remoteTools) {
      String sourceName = validateSourceName(remote.name());
      if (!seenSourceNames.add(sourceName)) {
        throw new AiValidationException(
            RESOURCE, "mcp server returned duplicate tools for source name: " + sourceName);
      }
      String toolName = requireToolName(server, sourceName);
      if (!seenToolNames.add(toolName)) {
        throw new AiValidationException(
            RESOURCE,
            "mcp server returns tools that normalize to the same model name: " + toolName);
      }

      McpTool candidate = new McpTool();
      candidate.setName(toolName);
      candidate.setServerName(server.getName());
      candidate.setSourceName(sourceName);
      candidate.setDescription(requireDescription(remote.description(), sourceName));
      candidate.setInputSchemaJson(canonicalizeSchema(remote.inputSchemaJson(), sourceName));
      candidates.add(candidate);
    }
    return List.copyOf(candidates);
  }

  private List<McpToolDefinition> listRemoteTools(McpServer server) {
    Map<String, String> resolvedHeaders =
        McpConfigParser.resolveHeaders(server.getHeaders(), envProvider);
    RemoteMcpConfig config = new RemoteMcpConfig(server.getUrl(), resolvedHeaders);
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
      // 复用带 object 校验的入口：数组/标量等非 object root 必须确定性拒绝。
      return McpInputSchemaConverter.toCanonicalJson(inputSchemaJson, RESOURCE);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, "mcp tool input schema is invalid: " + sourceName);
    }
  }

  private static String requireToolName(McpServer server, String sourceName) {
    try {
      return McpToolNameNormalizer.requireModelToolName(server.getName(), sourceName);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage());
    }
  }
}
