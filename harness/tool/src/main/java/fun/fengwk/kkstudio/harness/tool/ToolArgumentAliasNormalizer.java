package fun.fengwk.kkstudio.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.List;
import java.util.Set;

/**
 * 仅对内建文件模型工具生效的 path 参数别名归一化。
 *
 * <p>在 schema 校验与通用数字/null 归一化之前执行。当 canonical {@code path} 不存在， 且 {@code file}、{@code
 * filePath}、{@code file_path} 恰好一个存在时， 将该别名字段原值移动为 {@code path}；不 trim、不转换类型、不更改原始值。
 */
final class ToolArgumentAliasNormalizer {

  private static final Set<String> ALLOWLIST =
      Set.of(
          "read",
          "write",
          "edit",
          "find",
          "grep",
          "cloud_read",
          "cloud_write",
          "cloud_edit",
          "cloud_find",
          "cloud_grep");

  private static final List<String> ALIASES = List.of("file", "filePath", "file_path");

  private static final String CANONICAL_PATH = "path";

  private ToolArgumentAliasNormalizer() {}

  static String normalize(String toolName, String argumentsJson) {
    if (!ALLOWLIST.contains(toolName)) {
      return argumentsJson;
    }
    ObjectNode objectNode = (ObjectNode) JsonValues.readTree(argumentsJson);
    if (objectNode.has(CANONICAL_PATH)) {
      return argumentsJson;
    }
    String matchedAlias = null;
    for (String alias : ALIASES) {
      if (objectNode.has(alias)) {
        if (matchedAlias != null) {
          return argumentsJson;
        }
        matchedAlias = alias;
      }
    }
    if (matchedAlias == null) {
      return argumentsJson;
    }
    JsonNode value = objectNode.remove(matchedAlias);
    objectNode.set(CANONICAL_PATH, value);
    return JsonValues.write(objectNode);
  }
}
