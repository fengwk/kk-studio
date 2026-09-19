package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** {@code mcp_server.headers} JSON 文本与 header 映射之间的最小编解码。 */
final class McpHeadersJson {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

  private McpHeadersJson() {}

  static String encode(Map<String, String> headers) {
    try {
      return OBJECT_MAPPER.writeValueAsString(headers == null ? Map.of() : headers);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to encode MCP server headers", error);
    }
  }

  static Map<String, String> decode(String headersJson) {
    if (headersJson == null || headersJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, String> decoded = OBJECT_MAPPER.readValue(headersJson, STRING_MAP);
      return decoded == null ? Map.of() : Map.copyOf(decoded);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to decode MCP server headers", error);
    }
  }
}
