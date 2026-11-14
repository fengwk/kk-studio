package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogAbilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogLimitDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 模型候选的能力与上限字段解析：{@code limit}、{@code capabilities} 与 {@code modalities}。 */
final class ModelsDevModelFields {

  private ModelsDevModelFields() {}

  /** 上下文/输入/输出上限；三项都缺省时视为没有上限声明。 */
  static ModelCatalogLimitDTO limit(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      JsonNode object = CatalogJson.requireObject(node, "limit");
      Integer context = CatalogJson.nonNegativeInt(object.get("context"), "limit.context");
      Integer input = CatalogJson.nonNegativeInt(object.get("input"), "limit.input");
      Integer output = CatalogJson.nonNegativeInt(object.get("output"), "limit.output");
      if (context == null && input == null && output == null) {
        return null;
      }
      ModelCatalogLimitDTO limit = new ModelCatalogLimitDTO();
      limit.setContext(context);
      limit.setInput(input);
      limit.setOutput(output);
      return limit;
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
      return null;
    }
  }

  /** 工具/推理能力与输入模态；无法识别的值只记录问题，不影响其余字段。 */
  static ModelCatalogAbilitiesDTO abilities(JsonNode model, List<String> issues) {
    ModelCatalogAbilitiesDTO abilities = new ModelCatalogAbilitiesDTO();
    JsonNode capabilities = model.get("capabilities");
    if (capabilities != null && capabilities.isObject()) {
      abilities.setTools(capability(capabilities.get("tools"), "capabilities.tools", issues));
      abilities.setReasoning(
          capability(capabilities.get("reasoning"), "capabilities.reasoning", issues));
    }
    List<ModelInputModality> modalities = modalities(model.get("modalities"), issues);
    if (modalities != null && !modalities.isEmpty()) {
      abilities.setInputModalities(modalities);
    }
    return abilities;
  }

  private static Boolean capability(JsonNode node, String name, List<String> issues) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      if (node.isObject()) {
        return CatalogJson.bool(node.get("supported"), name + ".supported");
      }
      return CatalogJson.bool(node, name);
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
      return null;
    }
  }

  private static List<ModelInputModality> modalities(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode input = node.get("input");
    if (input == null || input.isNull()) {
      return null;
    }
    if (!input.isArray()) {
      issues.add("modalities.input must be an array");
      return null;
    }
    List<ModelInputModality> modalities = new ArrayList<>();
    for (JsonNode value : input) {
      if (!value.isString()) {
        issues.add("modalities.input values must be text");
        continue;
      }
      ModelInputModality modality = modality(value.stringValue());
      if (modality == null) {
        issues.add("unsupported input modality: " + value.stringValue());
      } else if (!modalities.contains(modality)) {
        modalities.add(modality);
      }
    }
    return modalities;
  }

  private static ModelInputModality modality(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "text" -> ModelInputModality.TEXT;
      case "image" -> ModelInputModality.IMAGE;
      case "audio" -> ModelInputModality.AUDIO;
      case "video" -> ModelInputModality.VIDEO;
      case "pdf", "document", "application/pdf" -> ModelInputModality.DOCUMENT;
      default -> null;
    };
  }
}
