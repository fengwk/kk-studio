package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindingsParser;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiUpdateDTO;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ComfyuiWorkflowApi 写路径的入参校验、标准化与实体组装。
 *
 * @author fengwk
 */
@Component
final class ComfyuiWorkflowApiMutationFactory {

  /** apiName：仅 [a-z0-9-]，必须以字母开头，最长 64 字符。 */
  static final Pattern API_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

  static final int MAX_NAME_LENGTH = 128;
  private static final int MAX_DESCRIPTION_LENGTH = 512;

  private final ComfyuiWorkflowApiBindingsParser bindingsParser;

  ComfyuiWorkflowApiMutationFactory(ComfyuiWorkflowApiBindingsParser bindingsParser) {
    if (bindingsParser == null) {
      throw new IllegalArgumentException("bindingsParser must not be null");
    }
    this.bindingsParser = bindingsParser;
  }

  Mutation newCreateMutation(ComfyuiWorkflowApiCreateDTO createDTO) {
    return newMutation(createDTO, true, null);
  }

  Mutation newUpdateMutation(String currentApiName, ComfyuiWorkflowApiUpdateDTO updateDTO) {
    requireNonBlank(currentApiName, "currentApiName");
    return newMutation(updateDTO, false, currentApiName);
  }

  ComfyuiWorkflowApi newWorkflow(Mutation mutation) {
    requireNonNull(mutation, "mutation");
    ComfyuiWorkflowApi row = new ComfyuiWorkflowApi();
    row.setId(UUID.randomUUID());
    apply(row, mutation);
    return row;
  }

  void apply(ComfyuiWorkflowApi row, Mutation mutation) {
    requireNonNull(row, "row");
    requireNonNull(mutation, "mutation");
    row.setApiName(mutation.apiName());
    row.setName(mutation.name());
    row.setDescription(mutation.description());
    row.setWorkflowJson(mutation.workflowJson());
    row.setInputBindingsJson(mutation.inputBindingsJson());
    row.setDefaultSelector(mutation.defaultSelector());
    row.setEnabled(mutation.enabled());
  }

  private Mutation newMutation(
      ComfyuiWorkflowApiEditablePropertiesDTO properties,
      boolean requireApiName,
      String fallbackApiName) {
    validateEditable(properties, requireApiName);
    String apiName = normalizeApiName(properties.getApiName(), fallbackApiName);
    String workflowJson = trimToNull(properties.getWorkflowJson());
    String inputBindingsJson = trimToNull(properties.getInputBindingsJson());
    bindingsParser.parse(workflowJson, inputBindingsJson, properties.getDefaultSelector());
    return new Mutation(
        apiName,
        trimToNull(properties.getName()),
        trimToNull(properties.getDescription()),
        workflowJson,
        inputBindingsJson,
        trimToNull(properties.getDefaultSelector()),
        toEnabled(properties.getEnabled()));
  }

  private void validateEditable(
      ComfyuiWorkflowApiEditablePropertiesDTO properties, boolean requireApiName) {
    if (properties == null) {
      throw new IllegalArgumentException("comfyui workflow api body must not be null");
    }
    if (requireApiName && trimToNull(properties.getApiName()) == null) {
      throw new IllegalArgumentException("apiName must not be blank");
    }
    if (trimToNull(properties.getWorkflowJson()) == null) {
      throw new IllegalArgumentException("workflowJson must not be blank");
    }
    if (trimToNull(properties.getInputBindingsJson()) == null) {
      throw new IllegalArgumentException("inputBindingsJson must not be blank");
    }
    String name = trimToNull(properties.getName());
    if (name == null) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " chars");
    }
    String description = trimToNull(properties.getDescription());
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalArgumentException(
          "description must not exceed " + MAX_DESCRIPTION_LENGTH + " chars");
    }
    String apiName = trimToNull(properties.getApiName());
    if (apiName != null && !API_NAME_PATTERN.matcher(apiName).matches()) {
      throw new IllegalArgumentException(
          "apiName must match " + API_NAME_PATTERN.pattern() + ": " + apiName);
    }
  }

  private static String normalizeApiName(String raw, String fallback) {
    String apiName = trimToNull(raw);
    if (apiName == null) {
      apiName = fallback;
    }
    if (apiName == null) {
      throw new IllegalArgumentException("apiName must not be blank");
    }
    if (!API_NAME_PATTERN.matcher(apiName).matches()) {
      throw new IllegalArgumentException(
          "apiName must match " + API_NAME_PATTERN.pattern() + ": " + apiName);
    }
    return apiName;
  }

  private static Boolean toEnabled(Boolean raw) {
    return raw == null ? Boolean.FALSE : raw;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  record Mutation(
      String apiName,
      String name,
      String description,
      String workflowJson,
      String inputBindingsJson,
      String defaultSelector,
      Boolean enabled) {}
}
