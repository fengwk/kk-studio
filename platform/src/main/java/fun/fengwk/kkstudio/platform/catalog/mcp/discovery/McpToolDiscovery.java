package fun.fengwk.kkstudio.platform.catalog.mcp.discovery;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpToolNameNormalizer;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpConnectionSpec;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpRemoteToolSpec;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClient;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 事务外工具发现与完整校验。
 *
 * <p>发现流程：按事务外 server 快照创建 per-call client（触发 initialize 握手）→ tools/list → 对每个远端工具依次校验 source
 * name（可规范化）、description 非空白、JSON input schema 为 object，并生成模型可见工具名与 model_name 冲突检测。
 * 全部校验通过才返回候选；任何失败抛 {@link AiValidationException}，DB 完全不变。client 由本类以 try-with-resources 恰好关闭一次。
 */
public final class McpToolDiscovery {

  private static final String RESOURCE = "mcp_server";

  /** 远端原始工具名最大长度（与 mcp_tool.source_name 列宽一致）。 */
  public static final int SOURCE_NAME_MAX_LENGTH = 128;

  private final Function<McpConnectionSpec, McpToolClient> clientFactory;

  private McpToolDiscovery(Function<McpConnectionSpec, McpToolClient> clientFactory) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  /** 以生产 client 工厂创建发现服务。 */
  public static McpToolDiscovery withFactory(McpToolClientFactory factory) {
    Objects.requireNonNull(factory, "factory");
    return new McpToolDiscovery(factory::create);
  }

  /** 以显式 client 供应函数创建发现服务（测试替身入口）。 */
  public static McpToolDiscovery withProvider(Function<McpConnectionSpec, McpToolClient> provider) {
    return new McpToolDiscovery(provider);
  }

  /**
   * 执行发现并返回完整候选集；不做任何持久化。
   *
   * @param server 事务外读取的 server 快照
   * @param existingTools DB 中既有工具行（用于保留稳定 UUID/model_name）
   * @param repository model_name 冲突检测仓库；null 表示跳过全局冲突检测（纯单元测试）
   * @param excludeToolId refresh 场景冲突检测排除的本行；create 场景传 null
   */
  public DiscoveryResult discover(
      McpServer server,
      List<McpTool> existingTools,
      McpServerRepository repository,
      UUID excludeToolId) {
    Objects.requireNonNull(server, "server");
    List<McpRemoteToolSpec> remoteTools = listRemoteTools(server);
    Map<String, McpTool> existingBySource = new LinkedHashMap<>();
    for (McpTool existing : existingTools) {
      existingBySource.put(existing.getSourceName(), existing);
    }
    Set<String> seenSourceNames = new HashSet<>();
    Set<String> seenModelNames = new HashSet<>();
    List<McpTool> candidates = new ArrayList<>(remoteTools.size());
    for (McpRemoteToolSpec remote : remoteTools) {
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
      McpTool existing = existingBySource.get(sourceName);
      if (existing != null) {
        // 稳定身份：既有 (serverId, sourceName) 保留 UUID 与 model_name。
        candidate.setId(existing.getId());
        candidate.setModelName(existing.getModelName());
      } else {
        candidate.setId(UUID.randomUUID());
        candidate.setModelName(modelName);
      }
      candidate.setServerId(server.getId());
      candidate.setSourceName(sourceName);
      candidate.setDescription(description);
      candidate.setInputSchemaJson(inputSchemaJson);
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
    return new DiscoveryResult(List.copyOf(candidates), existingBySource);
  }

  private List<McpRemoteToolSpec> listRemoteTools(McpServer server) {
    McpConnectionSpec spec =
        new McpConnectionSpec(server.getUrl(), server.getBearerToken(), server.getTimeoutMillis());
    // per-call client：发现结束即关闭，绝不缓存连接。
    try (McpToolClient client = clientFactory.apply(spec)) {
      List<McpRemoteToolSpec> tools = client.listTools();
      return tools == null ? List.of() : tools;
    } catch (RuntimeException error) {
      throw new AiValidationException(RESOURCE, "MCP server discovery failed.", error);
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
      throw new AiValidationException(
          RESOURCE, "mcp tool input schema is invalid: " + sourceName, error);
    }
  }

  private static String requireModelName(McpServer server, String sourceName) {
    try {
      return McpToolNameNormalizer.requireModelToolName(server.getName(), sourceName);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
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

  /** 发现结果：完整候选集与既有 (sourceName → tool) 快照。 */
  public record DiscoveryResult(List<McpTool> candidates, Map<String, McpTool> existingBySource) {

    public DiscoveryResult {
      candidates = List.copyOf(candidates);
      existingBySource = Map.copyOf(existingBySource);
    }
  }
}
