package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.comfyui.exception.WorkflowException;
import fun.fengwk.convention4j.comfyui.workflow.Workflow;
import fun.fengwk.convention4j.comfyui.workflow.WorkflowNode;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.Binding;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把持久化的 workflowJson + inputBindingsJson 解析成运行时可以直接使用的 {@link ComfyuiWorkflowApiBindings}。
 *
 * <p>校验内容包括：
 *
 * <ul>
 *   <li>inputBindingsJson 必须是 JSON 数组，且每项包含必填字段；
 *   <li>name 唯一；kind 仅接受 parameter / file；
 *   <li>file 类型不接受 URL/path-runtime 表达，只接受 S3 对象 key；
 *   <li>parameter 类型可选 valueType ∈ string/integer/number/boolean/json；
 *   <li>所有引用的 nodeId 必须存在、inputName 必须存在于该节点的 inputs 中；
 *   <li>workflowJson 必须可以被 {@link Workflow#fromApiJson(String)} 解析。
 * </ul>
 *
 * @author fengwk
 */
public class ComfyuiWorkflowApiBindingsParser {

  private final ObjectMapper objectMapper;

  public ComfyuiWorkflowApiBindingsParser(ObjectMapper objectMapper) {
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper must not be null");
    }
    this.objectMapper = objectMapper;
  }

  /** 解析并校验，返回绑定模型；任何失败都会以 {@link IllegalArgumentException} 上抛。 */
  public ComfyuiWorkflowApiBindings parse(
      String workflowJson, String inputBindingsJson, String defaultSelector) {
    Workflow workflow = parseWorkflow(workflowJson);
    List<Binding> bindings = parseBindings(workflow, inputBindingsJson);
    String validatedSelector = ComfyuiWorkflowApiSelectorValidator.validate(defaultSelector);
    return new ComfyuiWorkflowApiBindings(workflow, bindings, validatedSelector);
  }

  private Workflow parseWorkflow(String workflowJson) {
    if (workflowJson == null || workflowJson.isBlank()) {
      throw new IllegalArgumentException("workflowJson must not be blank");
    }
    try {
      return Workflow.fromApiJson(workflowJson);
    } catch (WorkflowException e) {
      throw new IllegalArgumentException(
          "workflowJson is not a valid ComfyUI API format: " + e.getMessage(), e);
    }
  }

  private List<Binding> parseBindings(Workflow workflow, String inputBindingsJson) {
    if (inputBindingsJson == null || inputBindingsJson.isBlank()) {
      return List.of();
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(inputBindingsJson);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "inputBindingsJson must be valid JSON: " + e.getMessage(), e);
    }
    if (!root.isArray()) {
      throw new IllegalArgumentException("inputBindingsJson must be a JSON array");
    }
    List<Binding> result = new ArrayList<>();
    Set<String> seenNames = new HashSet<>();
    int order = 0;
    for (Iterator<JsonNode> it = root.elements(); it.hasNext(); ) {
      JsonNode element = it.next();
      String position = "inputBindingsJson[" + order + "]";
      Binding binding = parseBinding(position, element, workflow, seenNames);
      result.add(binding);
      order++;
    }
    return result;
  }

  private Binding parseBinding(
      String position, JsonNode element, Workflow workflow, Set<String> seenNames) {
    if (element == null || !element.isObject()) {
      throw new IllegalArgumentException(position + " must be a JSON object");
    }
    String name = requiredText(element, "name", position);
    if (!seenNames.add(name)) {
      throw new IllegalArgumentException(
          "inputBindingsJson contains duplicate binding name: " + name);
    }
    String kindRaw = requiredText(element, "kind", position);
    ComfyuiWorkflowApiBindings.Kind kind;
    try {
      kind = ComfyuiWorkflowApiBindings.Kind.valueOf(kindRaw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          position + ".kind must be one of [parameter, file]: " + kindRaw, e);
    }
    String nodeId = requiredText(element, "nodeId", position);
    String inputName = requiredText(element, "inputName", position);
    boolean required = parseRequired(element, position);
    String description = optionalText(element, "description", position);
    WorkflowNode node = workflow.getNode(nodeId);
    if (node == null) {
      throw new IllegalArgumentException(
          position + " references unknown nodeId '" + nodeId + "' in workflowJson");
    }
    if (!node.getInputs().has(inputName)) {
      throw new IllegalArgumentException(
          position + " references unknown input '" + inputName + "' of node '" + nodeId + "'");
    }
    ComfyuiWorkflowApiBindings.ParameterOptions parameterOptions = null;
    if (kind == ComfyuiWorkflowApiBindings.Kind.PARAMETER) {
      parameterOptions = parseParameterOptions(position, element);
    } else {
      validateFileBinding(position, element);
    }
    return new ComfyuiWorkflowApiBindings.Binding(
        name, kind, nodeId, inputName, required, description, parameterOptions);
  }

  private ComfyuiWorkflowApiBindings.ParameterOptions parseParameterOptions(
      String position, JsonNode element) {
    JsonNode valueTypeNode = element.get("valueType");
    ComfyuiWorkflowApiBindings.ValueType valueType = null;
    if (valueTypeNode != null && !valueTypeNode.isNull()) {
      if (!valueTypeNode.isTextual()) {
        throw new IllegalArgumentException(position + ".valueType must be a string when present");
      }
      String raw = valueTypeNode.asText().trim();
      try {
        valueType = ComfyuiWorkflowApiBindings.ValueType.valueOf(raw.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            position
                + ".valueType must be one of "
                + "[string, integer, number, boolean, json]: "
                + raw,
            e);
      }
    }
    JsonNode defaultNode = element.get("defaultValue");
    Object defaultValue = null;
    if (defaultNode != null && !defaultNode.isNull()) {
      defaultValue = coerceDefaultValue(valueType, defaultNode, position);
    }
    return new ComfyuiWorkflowApiBindings.ParameterOptions(valueType, defaultValue);
  }

  private Object coerceDefaultValue(
      ComfyuiWorkflowApiBindings.ValueType valueType, JsonNode defaultNode, String position) {
    if (valueType == null) {
      // 未声明 valueType 时，使用 JsonNode 原值；后续使用方负责再次校验。
      return defaultNode;
    }
    switch (valueType) {
      case STRING:
        if (!defaultNode.isTextual()) {
          throw new IllegalArgumentException(
              position + ".defaultValue must be a string when valueType=string");
        }
        return defaultNode.asText();
      case INTEGER:
        if (!defaultNode.isIntegralNumber()) {
          throw new IllegalArgumentException(
              position + ".defaultValue must be an integer when valueType=integer");
        }
        return defaultNode.asLong();
      case NUMBER:
        if (!defaultNode.isNumber()) {
          throw new IllegalArgumentException(
              position + ".defaultValue must be a number when valueType=number");
        }
        return defaultNode.asDouble();
      case BOOLEAN:
        if (!defaultNode.isBoolean()) {
          throw new IllegalArgumentException(
              position + ".defaultValue must be a boolean when valueType=boolean");
        }
        return defaultNode.asBoolean();
      case JSON:
        return defaultNode;
      default:
        return defaultNode;
    }
  }

  private void validateFileBinding(String position, JsonNode element) {
    if (element.has("valueType") || element.has("defaultValue")) {
      throw new IllegalArgumentException(
          position
              + " (kind=file) must not declare valueType or defaultValue; runtime receives an S3"
              + " key");
    }
  }

  private static String requiredText(JsonNode element, String field, String position) {
    JsonNode node = element.get(field);
    if (node == null || node.isNull() || !node.isTextual()) {
      throw new IllegalArgumentException(position + "." + field + " must be a non-blank string");
    }
    String value = node.asText().trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException(position + "." + field + " must be a non-blank string");
    }
    return value;
  }

  private static String optionalText(JsonNode element, String field, String position) {
    JsonNode node = element.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException(position + "." + field + " must be a string when present");
    }
    String value = node.asText().trim();
    return value.isEmpty() ? null : value;
  }

  private static boolean parseRequired(JsonNode element, String position) {
    JsonNode node = element.get("required");
    if (node == null || node.isNull()) {
      return false;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException(position + ".required must be a boolean when present");
    }
    return node.asBoolean();
  }
}
