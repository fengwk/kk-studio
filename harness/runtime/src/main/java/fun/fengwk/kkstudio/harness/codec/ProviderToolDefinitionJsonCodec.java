package fun.fengwk.kkstudio.harness.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;

import java.util.Objects;
import java.util.Set;

/** SYSTEM 中模型可见工具定义的严格、确定性 JSON codec。 */
final class ProviderToolDefinitionJsonCodec {

  private static final String CONTEXT = "systemToolDefinition";
  private static final Set<String> FIELDS = Set.of("name", "description", "inputSchema");
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  ObjectNode encodeNode(ProviderToolDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    ObjectNode node = NODES.objectNode();
    node.put("name", definition.name());
    node.put("description", definition.description());
    node.set(
        "inputSchema", SCHEMA_CODEC.encodeNode(SCHEMA_CODEC.decode(definition.inputSchemaJson())));
    return node;
  }

  ProviderToolDefinition decodeNode(JsonNode value) {
    ObjectNode node = HistoryValueCodecs.requireObject(value, CONTEXT);
    JsonCodecSupport.requireFields(node, FIELDS, CONTEXT);
    String name = HistoryValueCodecs.requiredText(node, "name", CONTEXT);
    String description = HistoryValueCodecs.requiredText(node, "description", CONTEXT);
    InputSchema inputSchema =
        SCHEMA_CODEC.decodeNode(node.get("inputSchema"), CONTEXT + ".inputSchema");
    return new ProviderToolDefinition(name, description, SCHEMA_CODEC.encode(inputSchema));
  }
}
