package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;

/**
 * 面向携带路径参数的 coding tools 的窄范围、未公开的 pi-base 参数别名。
 *
 * <p>尽管每个 tool 契约都声明 {@code path}，部分 provider model 仍会发送 {@code filePath}。仅在严格 descriptor 校验前重写
 * 这一无歧义场景；同时发送两个名字的调用方仍会收到常规的 unknown-property 错误。
 */
public final class CodingToolArgumentAliases {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> PATH_TOOL_NAMES =
      Set.of(
          "read",
          "write",
          "edit",
          "grep",
          "find",
          "lsp_goto_definition",
          "lsp_workspace_symbols",
          "lsp_java_decompile");

  private CodingToolArgumentAliases() {}

  /** 对已知的路径型 tools，将唯一的 {@code filePath} 参数重写为 {@code path}。 */
  public static String normalize(String toolName, String argumentsJson) {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    if (!PATH_TOOL_NAMES.contains(toolName)) {
      return argumentsJson;
    }
    ObjectNode arguments = parseObject(argumentsJson);
    if (!arguments.has("filePath") || arguments.has("path")) {
      return argumentsJson;
    }
    JsonNode filePath = arguments.remove("filePath");
    arguments.set("path", filePath);
    try {
      return OBJECT_MAPPER.writeValueAsString(arguments);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot normalize tool arguments", error);
    }
  }

  private static ObjectNode parseObject(String argumentsJson) {
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(argumentsJson);
      if (!(parsed instanceof ObjectNode object)) {
        throw new IllegalArgumentException("argumentsJson must be a JSON object");
      }
      return object;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("argumentsJson must be valid JSON", error);
    }
  }
}
