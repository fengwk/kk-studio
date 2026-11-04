package fun.fengwk.kkstudio.harness.builtin.driver;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Builtin Driver 执行选择与模型身份的双向映射。
 *
 * <p>Builtin 的 Driver 标识为 {@code "builtin"}；其 Settings 形状精确为 {@code {"modelName": string,
 * "variant": string | null}}。
 */
public final class BuiltinExecution {

  public static final String DRIVER = "builtin";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> ALLOWED_FIELDS = Set.of("modelName", "variant");

  private BuiltinExecution() {}

  /** 将内核模型身份构造为 Builtin ExecutionSelection。 */
  public static ExecutionSelection of(ModelSelection model) {
    Objects.requireNonNull(model, "model");
    ObjectNode node = NODES.objectNode();
    node.put("modelName", model.modelName());
    if (model.variant() == null) {
      node.putNull("variant");
    } else {
      node.put("variant", model.variant());
    }
    try {
      return new ExecutionSelection(DRIVER, MAPPER.writeValueAsString(node));
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot serialize builtin execution settings", error);
    }
  }

  /** 从 ExecutionSelection 解析 Builtin 模型身份；要求 driver 为 builtin 且字段精确合法。 */
  public static ModelSelection model(ExecutionSelection selection) {
    Objects.requireNonNull(selection, "selection");
    if (!DRIVER.equals(selection.driver())) {
      throw new IllegalArgumentException(
          "driver must be " + DRIVER + " but was " + selection.driver());
    }
    return parseSettings(selection.settingsJson());
  }

  /** 解析 Builtin settings 文本为模型身份；严格校验字段与类型。 */
  public static ModelSelection parseSettings(String settingsJson) {
    Objects.requireNonNull(settingsJson, "settingsJson");
    JsonNode node;
    try {
      node = MAPPER.readTree(settingsJson);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("invalid builtin execution settings JSON", error);
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("builtin settings must be a JSON object");
    }
    Iterator<String> fieldNames = node.propertyNames().iterator();
    while (fieldNames.hasNext()) {
      String field = fieldNames.next();
      if (!ALLOWED_FIELDS.contains(field)) {
        throw new IllegalArgumentException("unrecognized field in builtin settings: " + field);
      }
    }
    if (!node.has("modelName") || !node.has("variant")) {
      throw new IllegalArgumentException(
          "builtin settings must contain both modelName and variant");
    }
    JsonNode modelNameNode = node.get("modelName");
    if (!modelNameNode.isTextual()) {
      throw new IllegalArgumentException("modelName must be a string");
    }
    String modelName = modelNameNode.asText();

    JsonNode variantNode = node.get("variant");
    String variant;
    if (variantNode.isNull()) {
      variant = null;
    } else if (variantNode.isTextual()) {
      variant = variantNode.asText();
    } else {
      throw new IllegalArgumentException("variant must be a string or null");
    }

    return new ModelSelection(modelName, variant);
  }
}
