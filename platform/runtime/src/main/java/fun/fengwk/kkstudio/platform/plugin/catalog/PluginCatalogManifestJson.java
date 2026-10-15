package fun.fengwk.kkstudio.platform.plugin.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code plugin_catalog_revision.manifest_json} 与 {@link PluginCatalogManifest} 之间的严格、确定性编解码。
 *
 * <p>编码按 pluginId 升序输出 {@code {plugins:{...}, catalogFingerprint}}，每个 entry 固定字段顺序 {@code
 * {configRevision, pluginCodeVersion, schemaFingerprint, capabilityFingerprint, authGeneration,
 * contextFingerprint}}，并受 1 MiB UTF-8 上限约束。解码拒绝重复字段、 尾随内容、 未知/缺失字段、错误类型、非正整数或溢出的 revision、非法指纹格式、
 * 非法 pluginId 与非法认证身份；任何失败都收敛为固定错误，既不附带底层 cause 也不回显原始 JSON。
 */
public final class PluginCatalogManifestJson {

  /** manifest JSON 的 UTF-8 字节上限。 */
  public static final int MAX_UTF8_BYTES = 1024 * 1024;

  private static final Set<String> ROOT_FIELDS = Set.of("plugins", "catalogFingerprint");
  private static final Set<String> ENTRY_FIELDS =
      Set.of(
          "configRevision",
          "pluginCodeVersion",
          "schemaFingerprint",
          "capabilityFingerprint",
          "authGeneration",
          "contextFingerprint");
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private PluginCatalogManifestJson() {}

  /** 把 manifest 编码为确定性 JSON 文本。 */
  public static String encode(PluginCatalogManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    ObjectNode root = NODES.objectNode();
    ObjectNode plugins = root.putObject("plugins");
    for (Map.Entry<String, PluginCatalogManifest.Entry> entry : manifest.plugins().entrySet()) {
      ObjectNode node = plugins.putObject(entry.getKey());
      node.put("configRevision", entry.getValue().configRevision());
      node.put("pluginCodeVersion", entry.getValue().pluginCodeVersion());
      node.put("schemaFingerprint", entry.getValue().schemaFingerprint());
      node.put("capabilityFingerprint", entry.getValue().capabilityFingerprint());
      node.put("authGeneration", entry.getValue().authGeneration().toString());
      node.put("contextFingerprint", entry.getValue().contextFingerprint());
    }
    if (manifest.catalogFingerprint() == null) {
      root.putNull("catalogFingerprint");
    } else {
      root.put("catalogFingerprint", manifest.catalogFingerprint());
    }
    String json = BoundedJsonWriter.write(root, MAX_UTF8_BYTES);
    if (json == null) {
      throw new IllegalArgumentException(
          "plugin catalog manifest exceeds " + MAX_UTF8_BYTES + " bytes");
    }
    return json;
  }

  /** 解码存储的 manifest JSON 文本；任何非法结构或非法记录都收敛为同一个固定错误。 */
  public static PluginCatalogManifest decode(String json) {
    try {
      return decodeStrict(json);
    } catch (RuntimeException error) {
      // 重复字段、尾随内容、未知/缺失字段、错误类型、非法 pluginId、非法指纹、非正/溢出 revision 与超限文本统一收敛，
      // 既不附带底层 cause 也不回显原始 JSON。
      throw invalid();
    }
  }

  private static PluginCatalogManifest decodeStrict(String json) {
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
      throw invalid();
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw invalid();
    }
    if (root == null || !root.isObject()) {
      throw invalid();
    }
    ObjectNode object = (ObjectNode) root;
    requireExactFields(object, ROOT_FIELDS);
    JsonNode pluginsNode = object.get("plugins");
    if (pluginsNode == null || !pluginsNode.isObject()) {
      throw invalid();
    }
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>();
    for (String pluginId : pluginsNode.propertyNames()) {
      JsonNode entryNode = pluginsNode.get(pluginId);
      if (entryNode == null || !entryNode.isObject()) {
        throw invalid();
      }
      ObjectNode entryObject = (ObjectNode) entryNode;
      requireExactFields(entryObject, ENTRY_FIELDS);
      plugins.put(
          pluginId,
          new PluginCatalogManifest.Entry(
              requirePositiveLong(entryObject.get("configRevision")),
              requireText(entryObject.get("pluginCodeVersion")),
              requireText(entryObject.get("schemaFingerprint")),
              requireText(entryObject.get("capabilityFingerprint")),
              requireUuid(entryObject.get("authGeneration")),
              requireText(entryObject.get("contextFingerprint"))));
    }
    return new PluginCatalogManifest(plugins, readFingerprint(object.get("catalogFingerprint")));
  }

  private static String readFingerprint(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw invalid();
    }
    return node.stringValue();
  }

  private static long requirePositiveLong(JsonNode node) {
    if (node == null
        || !node.isIntegralNumber()
        || !node.canConvertToLong()
        || node.longValue() <= 0) {
      throw invalid();
    }
    return node.longValue();
  }

  private static String requireText(JsonNode node) {
    if (node == null || !node.isString() || node.stringValue().isBlank()) {
      throw invalid();
    }
    return node.stringValue();
  }

  private static UUID requireUuid(JsonNode node) {
    String text = requireText(node);
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static void requireExactFields(ObjectNode object, Set<String> fields) {
    if (object.size() != fields.size()) {
      throw invalid();
    }
    for (String field : fields) {
      if (!object.has(field)) {
        throw invalid();
      }
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("stored plugin catalog manifest is invalid");
  }
}
