package fun.fengwk.kkstudio.harness.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * branch settings / settings command 共用的 execution selection 严格 JSON codec。
 *
 * <p>线上形状为：{@code {"driver": "...", "settings": {...}}}，其中 settings 必须是 JSON object。
 */
public final class ExecutionSelectionJsonCodec {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> FIELDS = orderedSet("driver", "settings");

  private static final ObjectMapper MAPPER = HarnessJson.STRICT_DECIMAL;

  private ExecutionSelectionJsonCodec() {}

  public static ObjectNode encode(ExecutionSelection selection) {
    Objects.requireNonNull(selection, "selection");
    ObjectNode node = NODES.objectNode();
    node.put("driver", selection.driver());
    try {
      node.set("settings", MAPPER.readTree(selection.settingsJson()));
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot decode normalized settingsJson", error);
    }
    return node;
  }

  public static ExecutionSelection decode(JsonNode value, String context) {
    ObjectNode node = JsonCodecSupport.object(value, context);
    JsonCodecSupport.requireFields(node, FIELDS, context);
    String driver = requiredText(node, "driver", context);
    JsonNode settingsNode = node.get("settings");
    if (settingsNode == null || !settingsNode.isObject()) {
      throw new IllegalArgumentException(context + ".settings must be a JSON object");
    }
    String settingsJson;
    try {
      settingsJson = MAPPER.writeValueAsString(settingsNode);
    } catch (JacksonException error) {
      throw new IllegalArgumentException(context + ".settings could not be written as JSON", error);
    }
    return new ExecutionSelection(driver, settingsJson);
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.stringValue();
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Collections.unmodifiableSet(set);
  }
}
