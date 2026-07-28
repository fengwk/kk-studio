package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;

/**
 * Narrow, unadvertised pi-base argument aliases for path-bearing coding tools.
 *
 * <p>Some provider models emit {@code filePath} even though every tool contract declares {@code
 * path}. Rewrite only the unambiguous case before strict descriptor validation; callers that send
 * both names still receive the normal unknown-property error.
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

  /** Rewrites a sole {@code filePath} argument to {@code path} for known path-bearing tools. */
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
