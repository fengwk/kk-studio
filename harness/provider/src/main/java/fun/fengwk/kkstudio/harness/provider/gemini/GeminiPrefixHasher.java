package fun.fengwk.kkstudio.harness.provider.gemini;

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
 * 负责计算 Gemini GenerateContent 请求的规范化 source prefix hash（SHA-256 小写十六进制）。
 *
 * <p>保证稳定字典序对象键与确定性序列化。
 */
final class GeminiPrefixHasher {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private GeminiPrefixHasher() {}

  static String calculateHash(JsonNode systemInstruction, ArrayNode tools, ArrayNode contents) {
    ObjectNode root = NODES.objectNode();
    root.put("version", 1);
    root.set("systemInstruction", systemInstruction != null ? systemInstruction : NODES.nullNode());
    root.set("tools", tools != null ? tools : NODES.arrayNode());
    root.set("contents", contents != null ? contents : NODES.arrayNode());

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
      return;
    }
    if (node.isBoolean()) {
      out.append(node.asBoolean());
      return;
    }
    if (node.isNumber()) {
      out.append(node.asText());
      return;
    }
    if (node.isTextual()) {
      writeJsonString(node.asText(), out);
      return;
    }
    if (node.isArray()) {
      out.append("[");
      boolean first = true;
      for (JsonNode elem : node) {
        if (!first) {
          out.append(",");
        }
        first = false;
        writeCanonical(elem, out);
      }
      out.append("]");
      return;
    }
    if (node.isObject()) {
      out.append("{");
      List<String> fieldNames = new ArrayList<>();
      Iterator<String> it = node.fieldNames();
      while (it.hasNext()) {
        fieldNames.add(it.next());
      }
      Collections.sort(fieldNames);
      boolean first = true;
      for (String name : fieldNames) {
        if (!first) {
          out.append(",");
        }
        first = false;
        writeJsonString(name, out);
        out.append(":");
        writeCanonical(node.get(name), out);
      }
      out.append("}");
    }
  }

  private static void writeJsonString(String text, StringBuilder out) {
    out.append("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
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
    out.append("\"");
  }
}
