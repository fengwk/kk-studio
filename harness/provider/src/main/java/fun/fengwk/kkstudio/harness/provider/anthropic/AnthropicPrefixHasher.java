package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * 负责计算 Anthropic Messages 的版本化 canonical prefix hash（SHA-256 小写十六进制）。
 *
 * <p>保证稳定字典序对象键、确定性 JSON 序列化，且完全排除 cache_control 标记。
 */
final class AnthropicPrefixHasher {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String CACHE_CONTROL_FIELD = "cache_control";

  private AnthropicPrefixHasher() {}

  static String calculateHash(ArrayNode systemBlocks, ArrayNode tools, ArrayNode priorMessages) {
    ObjectNode root = NODES.objectNode();
    root.put("version", 1);
    root.set("system", systemBlocks != null ? systemBlocks : NODES.arrayNode());
    root.set("tools", tools != null ? tools : NODES.arrayNode());
    root.set("messages", priorMessages != null ? priorMessages : NODES.arrayNode());

    StringBuilder buffer = new StringBuilder();
    writeCanonical(root, buffer);
    byte[] utf8Bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(utf8Bytes);
      return HexFormat.of().formatHex(hashBytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 digest algorithm not available", exception);
    }
  }

  static void writeCanonical(JsonNode node, StringBuilder out) {
    if (node == null || node.isNull()) {
      out.append("null");
    } else if (node.isObject()) {
      out.append('{');
      List<String> fieldNames = new ArrayList<>();
      Iterator<String> it = node.fieldNames();
      while (it.hasNext()) {
        String name = it.next();
        if (!CACHE_CONTROL_FIELD.equals(name)) {
          fieldNames.add(name);
        }
      }
      Collections.sort(fieldNames);
      boolean first = true;
      for (String fieldName : fieldNames) {
        if (!first) {
          out.append(',');
        }
        first = false;
        escapeString(fieldName, out);
        out.append(':');
        writeCanonical(node.get(fieldName), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      boolean first = true;
      for (JsonNode item : node) {
        if (!first) {
          out.append(',');
        }
        first = false;
        writeCanonical(item, out);
      }
      out.append(']');
    } else if (node.isTextual()) {
      escapeString(node.textValue(), out);
    } else if (node.isNumber()) {
      out.append(node.numberValue().toString());
    } else if (node.isBoolean()) {
      out.append(node.booleanValue() ? "true" : "false");
    } else {
      out.append(node.asText());
    }
  }

  private static void escapeString(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
