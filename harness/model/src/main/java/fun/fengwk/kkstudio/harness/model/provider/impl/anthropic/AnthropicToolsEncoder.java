package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemHostedToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolRemovalBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Anthropic Messages 的 tools 声明合并与中途 system 消息编码。
 *
 * <p>native tools 只接受官方 hosted tool（{@code web_search}/{@code web_fetch}/{@code code_execution} 带日期
 * 版本），runtime 客户端工具只能通过 {@link ProviderToolDefinition} 绑定；合并结果同时是 cache marker 与请求体大小的依据。
 */
final class AnthropicToolsEncoder {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String DEFERRED_PLACEHOLDER_NAME = "__kk_deferred_placeholder__";

  private AnthropicToolsEncoder() {}

  /**
   * 合并 native tools 与 runtime function tools：先保留 native 条目（server tools 等官方非 function 工具），再追加
   * runtime 工具；合并结果同时是 prefix hash、cache marker 与请求体大小的依据。
   */
  static ArrayNode mergeTools(
      ObjectNode root,
      List<ProviderToolDefinition> runtimeTools,
      boolean appendDeferredPlaceholder) {
    JsonNode nativeTools = root.get("tools");
    if (nativeTools != null && !nativeTools.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions tools must be a JSON array");
    }

    ArrayNode merged = NODES.arrayNode();
    Set<String> names = new HashSet<>();
    if (nativeTools != null) {
      for (int i = 0; i < nativeTools.size(); i++) {
        JsonNode nativeTool = nativeTools.get(i);
        if (nativeTool.has("cache_control")) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions must not override runtime field: tools[" + i + "].cache_control");
        }
        JsonNode type = nativeTool.path("type");
        JsonNode name = nativeTool.path("name");
        if (!nativeTool.isObject()
            || !type.isString()
            || !type.stringValue().matches("(web_search|web_fetch|code_execution)_[0-9]{8}")
            || !name.isString()
            || name.stringValue().isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools["
                  + i
                  + "] must be a supported hosted tool; bind client tools through runtime"
                  + " ProviderToolDefinition");
        }
        if (!names.add(name.stringValue())) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "protocolOptions tools[" + i + "] duplicates a tool name");
        }
        for (ProviderToolDefinition runtimeTool : runtimeTools) {
          if (name.stringValue().equals(runtimeTool.name())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "protocolOptions tools[" + i + "] name conflicts with runtime tool");
          }
        }
        merged.add(nativeTool.deepCopy());
      }
    }
    for (ProviderToolDefinition tool : runtimeTools) {
      if (appendDeferredPlaceholder && DEFERRED_PLACEHOLDER_NAME.equals(tool.name())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "runtime tools must not use the reserved deferred placeholder name");
      }
      merged.add(encodeTool(tool));
    }
    if (appendDeferredPlaceholder) {
      if (!names.add(DEFERRED_PLACEHOLDER_NAME)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "runtime tools must not use the reserved deferred placeholder name");
      }
      merged.add(encodeDeferredPlaceholder());
    }
    return merged;
  }

  private static ObjectNode encodeDeferredPlaceholder() {
    ObjectNode placeholder = NODES.objectNode();
    placeholder.put("name", DEFERRED_PLACEHOLDER_NAME);
    placeholder.put("description", "Reserved placeholder. Never available. Never call this.");
    ObjectNode inputSchema = placeholder.putObject("input_schema");
    inputSchema.put("type", "object");
    inputSchema.putObject("properties");
    inputSchema.putArray("required");
    placeholder.put("defer_loading", true);
    return placeholder;
  }

  private static ObjectNode encodeTool(ProviderToolDefinition tool) {
    ObjectNode toolNode = NODES.objectNode();
    toolNode.put("name", tool.name());
    if (tool.description() != null && !tool.description().isBlank()) {
      toolNode.put("description", tool.description());
    }
    JsonNode schemaNode;
    try {
      schemaNode = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool inputSchemaJson is not valid JSON");
    }
    if (!schemaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool input_schema must be a JSON object");
    }
    toolNode.set("input_schema", schemaNode);
    return toolNode;
  }

  static ObjectNode encodeSystemMessage(ProviderMessage message) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "system");
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      if (block instanceof ProviderTextBlock textBlock) {
        ObjectNode text = NODES.objectNode();
        text.put("type", "text");
        text.put("text", textBlock.text());
        contents.add(text);
      } else if (block instanceof ProviderSystemToolAdditionBlock addition) {
        contents.add(encodeToolAddition(encodeTool(addition.tool())));
      } else if (block instanceof ProviderSystemHostedToolAdditionBlock addition) {
        contents.add(encodeToolAddition(parseHostedToolDefinition(addition.definitionJson())));
      } else if (block instanceof ProviderSystemToolRemovalBlock removal) {
        ObjectNode tool = NODES.objectNode();
        tool.put("type", "tool_reference");
        tool.put("name", removal.name());
        ObjectNode node = NODES.objectNode();
        node.put("type", "tool_removal");
        node.set("tool", tool);
        contents.add(node);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported content in Anthropic system message: " + block.getClass().getSimpleName());
      }
    }
    return msgNode;
  }

  private static ObjectNode encodeToolAddition(ObjectNode definition) {
    ObjectNode tool = NODES.objectNode();
    tool.put("type", "tool_definition");
    tool.set("definition", definition);
    ObjectNode addition = NODES.objectNode();
    addition.put("type", "tool_addition");
    addition.set("tool", tool);
    return addition;
  }

  private static ObjectNode parseHostedToolDefinition(String definitionJson) {
    try {
      JsonNode definition = OBJECT_MAPPER.readTree(definitionJson);
      if (definition.isObject() && definition.path("name").isString()) {
        return (ObjectNode) definition;
      }
    } catch (JacksonException ignored) {
      // The durable source is validated as an object when materialized.
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "hosted tool definition must be a JSON object with a name");
  }
}
