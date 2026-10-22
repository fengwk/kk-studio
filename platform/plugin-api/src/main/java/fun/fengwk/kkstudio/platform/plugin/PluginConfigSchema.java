package fun.fengwk.kkstudio.platform.plugin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.common.schema.ArraySchema;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.InputValidator;
import fun.fengwk.kkstudio.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.common.schema.SchemaElement;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plugin 的受控配置契约：普通值 schema、唯一默认值声明与秘密字段说明。
 *
 * <p>复用 {@link InputSchema}、{@link InputValidator} 与 {@link SchemaJsonCodec} 的受控类型，
 * 默认值只填补缺失字段；{@code secrets} 将与普通值分离的秘密字段映射到文本说明。
 *
 * <p>解析只给缺失字段递归补默认值：显式 {@code null} 不算缺失，不做数字字符串/布尔字符串容错，也不使用 {@code InputNormalizer}；默认数组整体作为默认值，
 * 不与用户数组逐项合并。原始文本、默认值文本与归一化文本都受 UTF-8 64 KiB 上限约束，任何解析失败都收敛为固定错误，不附带底层 cause 或原始 JSON。
 */
public final class PluginConfigSchema {

  private final InputSchema valuesSchema;
  private final ObjectNode defaults;
  private final String defaultsJson;
  private final Map<String, String> secrets;

  /**
   * @param valuesSchema 普通值的唯一字段与类型声明，必须禁止额外字段
   * @param defaultsJson 唯一默认值声明，可以是 sparse object；默认值本身必须类型正确且无未知字段
   * @param secrets secret field 到文本说明的冻结映射，与普通 properties 不可同名
   */
  public PluginConfigSchema(
      InputSchema valuesSchema, String defaultsJson, Map<String, String> secrets) {
    this.valuesSchema = Objects.requireNonNull(valuesSchema, "valuesSchema");
    InputSchema defaultsSchema = sparseSchema(valuesSchema);
    this.secrets = copySecrets(secrets, valuesSchema.properties());
    String defaultsText = PluginConfig.requireWithinBudget(defaultsJson, "defaultsJson");
    ObjectNode defaultsNode = PluginConfig.requireObjectNode(defaultsText, "defaultsJson");
    validate(defaultsText, defaultsSchema, "defaultsJson");
    this.defaults = defaultsNode;
    this.defaultsJson = writeBounded(defaultsNode, "defaultsJson");
  }

  public InputSchema valuesSchema() {
    return valuesSchema;
  }

  /** 唯一默认值声明的紧凑 JSON 文本。 */
  public String defaultsJson() {
    return defaultsJson;
  }

  public Map<String, String> secrets() {
    return secrets;
  }

  /**
   * 把一段用户配置归一化为最终配置：只给缺失字段递归补默认值，再按同一 {@code valuesSchema}（保留全部 required）校验，返回 canonical JSON 文本。
   *
   * @throws IllegalArgumentException 文本非严格 JSON object、类型不符、含未知字段或超过字节上限时
   */
  public String resolveValues(String valuesJson) {
    ObjectNode provided =
        PluginConfig.requireObjectNode(
            PluginConfig.requireWithinBudget(valuesJson, "valuesJson"), "valuesJson");
    applyDefaults(provided, valuesSchema.properties(), defaults);
    String normalized = writeBounded(provided, "resolved valuesJson");
    validate(normalized, valuesSchema, "valuesJson");
    return normalized;
  }

  /**
   * 认证声明必须与本 schema 自洽：{@link PluginAuthSpec.StaticKey} 只能命名本 schema 声明的秘密字段，其 {@code
   * contextFields} 只能命名 {@code valuesSchema} 已声明的普通顶层字段。
   */
  public void requireDeclaredSecrets(PluginAuthSpec authSpec) {
    Objects.requireNonNull(authSpec, "authSpec");
    if (authSpec instanceof PluginAuthSpec.StaticKey staticKey) {
      if (!secrets.containsKey(staticKey.secretField())) {
        throw new IllegalStateException(
            "StaticKey secretField must be declared in the plugin schema secrets: "
                + staticKey.secretField());
      }
      for (String contextField : staticKey.contextFields()) {
        if (!valuesSchema.properties().containsKey(contextField)) {
          throw new IllegalStateException(
              "StaticKey contextFields must be declared in the plugin values schema: "
                  + contextField);
        }
      }
    }
  }

  /** 默认值可以是 sparse object；各层仍按声明类型校验，且配置 Schema 必须拒绝未知字段。 */
  private static InputSchema sparseSchema(InputSchema schema) {
    requireClosedObject(schema.additionalProperties());
    return new InputSchema(
        schema.description(), sparseProperties(schema.properties()), Set.of(), false);
  }

  private static Map<String, SchemaElement> sparseProperties(
      Map<String, SchemaElement> properties) {
    Map<String, SchemaElement> viewed = new LinkedHashMap<>();
    for (Map.Entry<String, SchemaElement> entry : properties.entrySet()) {
      viewed.put(entry.getKey(), sparseElement(entry.getValue()));
    }
    return viewed;
  }

  private static SchemaElement sparseElement(SchemaElement element) {
    if (element instanceof ObjectSchema objectSchema) {
      requireClosedObject(objectSchema.additionalProperties());
      return new ObjectSchema(
          objectSchema.description(), sparseProperties(objectSchema.properties()), Set.of(), false);
    }
    if (element instanceof ArraySchema arraySchema) {
      return new ArraySchema(arraySchema.description(), sparseElement(arraySchema.items()));
    }
    return element;
  }

  private static void requireClosedObject(boolean additionalProperties) {
    if (additionalProperties) {
      throw new IllegalArgumentException("valuesSchema objects must reject additional properties");
    }
  }

  private static void validate(String json, InputSchema schema, String field) {
    try {
      InputValidator.validate(json, schema);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must match the configuration schema");
    }
  }

  /** 有界 canonical 编码：在完整分配前按 64 KiB 上限中止，超限即固定错误。 */
  private static String writeBounded(ObjectNode node, String field) {
    String text = BoundedJsonWriter.write(node, PluginConfig.MAX_VALUES_UTF8_BYTES);
    if (text == null) {
      throw new IllegalArgumentException(
          field + " must not exceed " + PluginConfig.MAX_VALUES_UTF8_BYTES + " bytes");
    }
    return text;
  }

  private static void applyDefaults(
      ObjectNode target, Map<String, SchemaElement> properties, ObjectNode defaults) {
    for (Map.Entry<String, SchemaElement> entry : properties.entrySet()) {
      String name = entry.getKey();
      SchemaElement schema = entry.getValue();
      // 显式 null 与任何已出现字段都视为「已提供」，绝不覆盖；只有缺失字段才补默认值。
      if (target.has(name)) {
        if (target.get(name).isObject() && schema instanceof ObjectSchema objectSchema) {
          JsonNode defaultChild = defaults.get(name);
          if (defaultChild != null && defaultChild.isObject()) {
            applyDefaults(
                (ObjectNode) target.get(name),
                objectSchema.properties(),
                (ObjectNode) defaultChild);
          }
        }
        continue;
      }
      JsonNode defaultChild = defaults.get(name);
      if (defaultChild != null) {
        // 默认数组整体作为默认值：直接写入，不与用户数组逐项合并。
        target.set(name, defaultChild.deepCopy());
      }
    }
  }

  private static Map<String, String> copySecrets(
      Map<String, String> secrets, Map<String, SchemaElement> properties) {
    Objects.requireNonNull(secrets, "secrets");
    Map<String, String> copied = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : secrets.entrySet()) {
      String field = Objects.requireNonNull(entry.getKey(), "secrets[]");
      String description = entry.getValue();
      if (field.isBlank() || !field.equals(field.strip())) {
        throw new IllegalArgumentException("secrets must use non-blank field names");
      }
      if (description == null || description.isBlank()) {
        throw new IllegalArgumentException("secrets must describe every secret field");
      }
      if (properties.containsKey(field)) {
        throw new IllegalArgumentException("secrets must not collide with ordinary properties");
      }
      copied.put(field, description);
    }
    return Map.copyOf(copied);
  }
}
