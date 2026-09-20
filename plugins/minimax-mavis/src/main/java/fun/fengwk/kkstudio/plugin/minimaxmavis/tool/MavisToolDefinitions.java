package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapability;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 15 项 Mavis 能力的静态工具资源加载器。
 *
 * <p>每项能力对应一个 classpath 资源 {@code tools/<capability-id>.json}，其中只包含 {@code description} 与 {@code
 * inputSchema}。加载是严格的：字段集合必须精确匹配、description 必须非空、schema 必须能通过 {@link SchemaJsonCodec} 与 {@link
 * InputSchema} 的构造约束。任何不合规都在启动期失败，避免把非法 schema 交给模型。
 */
public final class MavisToolDefinitions {

  private static final String RESOURCE_ROOT = "fun/fengwk/kkstudio/plugin/minimaxmavis/tools/";
  private static final Set<String> ALLOWED_FIELDS = Set.of("description", "inputSchema");
  private static final String DESCRIPTION_FIELD = "description";
  private static final String INPUT_SCHEMA_FIELD = "inputSchema";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private MavisToolDefinitions() {}

  /** 按能力加载并校验工具定义。 */
  public static MavisToolDefinition load(MavisCapability capability) {
    String resource = RESOURCE_ROOT + capability.id() + ".json";
    String content;
    try (InputStream stream =
        MavisToolDefinitions.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("missing MiniMax Mavis tool resource: " + resource);
      }
      content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException(
          "cannot read MiniMax Mavis tool resource: " + resource, error);
    }
    return decode(capability, content, resource);
  }

  /** 按能力枚举顺序加载全部 15 项工具定义。 */
  public static Map<MavisCapability, MavisToolDefinition> loadAll() {
    Map<MavisCapability, MavisToolDefinition> definitions = new EnumMap<>(MavisCapability.class);
    for (MavisCapability capability : MavisCapability.values()) {
      definitions.put(capability, load(capability));
    }
    return definitions;
  }

  /** 校验一份工具资源内容；{@code context} 只用于定位出错的资源。 */
  static MavisToolDefinition decode(MavisCapability capability, String content, String context) {
    JsonNode root;
    try {
      root = MAPPER.readTree(content);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("malformed MiniMax Mavis tool resource: " + context, error);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalStateException("tool resource must be a JSON object: " + context);
    }
    for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      if (!ALLOWED_FIELDS.contains(name)) {
        throw new IllegalStateException(
            "unexpected field '" + name + "' in tool resource: " + context);
      }
    }
    JsonNode description = root.get(DESCRIPTION_FIELD);
    if (description == null || !description.isTextual() || description.textValue().isBlank()) {
      throw new IllegalStateException(
          "tool resource must declare a non-blank description: " + context);
    }
    JsonNode schemaNode = root.get(INPUT_SCHEMA_FIELD);
    if (schemaNode == null) {
      throw new IllegalStateException("tool resource must declare inputSchema: " + context);
    }
    InputSchema inputSchema;
    try {
      inputSchema = SCHEMA_CODEC.decodeNode(schemaNode, capability.id() + ".inputSchema");
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("invalid input schema in tool resource: " + context, error);
    }
    return new MavisToolDefinition(
        capability, capability.toolName(), description.textValue(), inputSchema);
  }
}
