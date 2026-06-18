package fun.fengwk.kkstudio.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolSchemaElement;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;
import lombok.Data;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ToolCallRequest 表示一次工具调用请求。
 *
 * @author fengwk
 */
@Data
public class ToolCallRequest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 工具调用唯一标识。
     */
    private final String toolCallId;

    /**
     * 工具名称。
     */
    private final String toolName;

    /**
     * 非空 JSON 参数字符串。
     */
    private final String argumentsJson;

    public ToolCallRequest(String toolCallId, String toolName, String argumentsJson) {
        this(toolCallId, toolName, argumentsJson, null);
    }

    public ToolCallRequest(String toolCallId, String toolName, String argumentsJson, ToolParamsSchema inputSchema) {
        this.toolCallId = requireNonBlank(toolCallId, "toolCallId");
        this.toolName = requireNonBlank(toolName, "toolName");
        NormalizedArguments normalizedArguments = normalizeArguments(argumentsJson);
        validateAgainstSchema(normalizedArguments.root(), inputSchema);
        this.argumentsJson = normalizedArguments.argumentsJson();
    }

    private static NormalizedArguments normalizeArguments(String argumentsJson) {
        String normalizedArguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(normalizedArguments);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("argumentsJson must be a JSON object");
            }
            return new NormalizedArguments(normalizedArguments, root);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("argumentsJson must be a valid JSON object", error);
        }
    }

    private static void validateAgainstSchema(JsonNode root, ToolParamsSchema inputSchema) {
        if (inputSchema == null) {
            return;
        }
        validateObject("$", root, inputSchema.getProperties(), inputSchema.getRequired(), inputSchema.getAdditionalProperties());
    }

    private static void validateObject(String path,
                                       JsonNode node,
                                       Map<String, ToolSchemaElement> properties,
                                       List<String> required,
                                       Boolean additionalProperties) {
        if (node == null || !node.isObject()) {
            throw schemaMismatch(path, "must be an object");
        }
        validateRequiredProperties(path, node, required);
        validateAdditionalProperties(path, node, properties, additionalProperties);
        if (properties == null || properties.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ToolSchemaElement> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            ToolSchemaElement schemaElement = entry.getValue();
            if (propertyName == null || schemaElement == null || !node.has(propertyName)) {
                continue;
            }
            validateSchemaElement(path + "." + propertyName, node.get(propertyName), schemaElement);
        }
    }

    private static void validateRequiredProperties(String path, JsonNode node, List<String> required) {
        if (required == null || required.isEmpty()) {
            return;
        }
        for (String propertyName : required) {
            if (propertyName == null || propertyName.isBlank()) {
                throw new IllegalArgumentException("invalid tool inputSchema: required property name must not be blank: " + path);
            }
            if (!node.has(propertyName)) {
                throw schemaMismatch(path + "." + propertyName, "is required");
            }
        }
    }

    private static void validateAdditionalProperties(String path,
                                                     JsonNode node,
                                                     Map<String, ToolSchemaElement> properties,
                                                     Boolean additionalProperties) {
        if (!Boolean.FALSE.equals(additionalProperties)) {
            return;
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (properties == null || properties.get(fieldName) == null) {
                throw schemaMismatch(path + "." + fieldName, "is not allowed by inputSchema");
            }
        }
    }

    private static void validateSchemaElement(String path, JsonNode node, ToolSchemaElement schemaElement) {
        if (schemaElement instanceof ToolStringSchema) {
            requireType(path, node, node != null && node.isTextual(), "string");
            return;
        }
        if (schemaElement instanceof ToolIntegerSchema) {
            requireType(path, node, node != null && node.isIntegralNumber(), "integer");
            return;
        }
        if (schemaElement instanceof ToolNumberSchema) {
            requireType(path, node, node != null && node.isNumber(), "number");
            return;
        }
        if (schemaElement instanceof ToolBooleanSchema) {
            requireType(path, node, node != null && node.isBoolean(), "boolean");
            return;
        }
        if (schemaElement instanceof ToolEnumSchema schema) {
            validateEnum(path, node, schema);
            return;
        }
        if (schemaElement instanceof ToolArraySchema schema) {
            validateArray(path, node, schema);
            return;
        }
        if (schemaElement instanceof ToolObjectSchema schema) {
            validateObject(path, node, schema.getProperties(), schema.getRequired(), schema.getAdditionalProperties());
            return;
        }
        throw new IllegalArgumentException("unsupported tool schema element: " + schemaElement.getClass().getName());
    }

    private static void validateEnum(String path, JsonNode node, ToolEnumSchema schema) {
        requireType(path, node, node != null && node.isTextual(), "string enum");
        List<String> enumValues = schema.getEnumValues();
        if (enumValues != null && !enumValues.isEmpty() && !enumValues.contains(node.asText())) {
            throw schemaMismatch(path, "must be one of " + enumValues);
        }
    }

    private static void validateArray(String path, JsonNode node, ToolArraySchema schema) {
        requireType(path, node, node != null && node.isArray(), "array");
        ToolSchemaElement itemSchema = schema.getItems();
        if (itemSchema == null) {
            return;
        }
        for (int i = 0; i < node.size(); i++) {
            validateSchemaElement(path + "[" + i + "]", node.get(i), itemSchema);
        }
    }

    private static void requireType(String path, JsonNode node, boolean valid, String expectedType) {
        if (!valid) {
            throw schemaMismatch(path, "must be " + expectedType + ", actual=" + actualType(node));
        }
    }

    private static String actualType(JsonNode node) {
        return node == null ? "missing" : node.getNodeType().name().toLowerCase();
    }

    private static IllegalArgumentException schemaMismatch(String path, String reason) {
        return new IllegalArgumentException("argumentsJson does not match inputSchema: " + path + " " + reason);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record NormalizedArguments(String argumentsJson, JsonNode root) {
    }

}
