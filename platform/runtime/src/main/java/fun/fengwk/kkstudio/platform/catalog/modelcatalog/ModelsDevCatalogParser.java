package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogCandidateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * models.dev 目录解析器：把 provider/model 两级 JSON 展开成可填表候选。
 *
 * <p>候选的 pricing 只有能完整保存与求值时才标记为 supported；{@code cost.text.input/output.price}、cache
 * read/write、可精确映射的 input-token ranges、service-tier/UTC time multipliers 与 date changes 的解析在 {@link
 * ModelsDevPricingParser}；{@code limit}/{@code capabilities}/{@code modalities} 在 {@link
 * ModelsDevModelFields}。
 */
@Component
public class ModelsDevCatalogParser {

  private static final String ID_PREFIX = "models_dev:";

  public List<ModelCatalogCandidateDTO> parse(String json) {
    JsonNode root = CatalogJson.parse(json);
    List<ModelCatalogCandidateDTO> candidates = new ArrayList<>();
    for (Map.Entry<String, JsonNode> providerEntry : root.properties()) {
      JsonNode provider = providerEntry.getValue();
      if (provider == null || !provider.isObject()) {
        continue;
      }
      String providerName = CatalogJson.text(provider.get("id"), providerEntry.getKey());
      JsonNode models = provider.get("models");
      if (models == null || !models.isObject()) {
        continue;
      }
      for (Map.Entry<String, JsonNode> modelEntry : models.properties()) {
        JsonNode model = modelEntry.getValue();
        if (model == null || !model.isObject()) {
          continue;
        }
        candidates.add(toCandidate(providerName, modelEntry.getKey(), model));
      }
    }
    return candidates;
  }

  private ModelCatalogCandidateDTO toCandidate(
      String provider, String fallbackModelId, JsonNode model) {
    String modelId = CatalogJson.text(model.get("id"), fallbackModelId);
    List<String> issues = new ArrayList<>();
    ModelCatalogCandidateDTO candidate = new ModelCatalogCandidateDTO();
    candidate.setId(ID_PREFIX + provider + ":" + modelId);
    candidate.setProvider(provider);
    candidate.setModelId(modelId);
    candidate.setName(CatalogJson.text(model.get("name"), modelId));
    candidate.setLimit(ModelsDevModelFields.limit(model.get("limit"), issues));
    candidate.setAbilities(ModelsDevModelFields.abilities(model, issues));
    candidate.setSupportIssues(issues);
    applyPricing(candidate, model.get("cost"), issues);
    return candidate;
  }

  /** 价格解析与校验都通过才算 supported；失败时清空 pricing，避免半成品冒充完整价格。 */
  private static void applyPricing(
      ModelCatalogCandidateDTO candidate, JsonNode cost, List<String> issues) {
    try {
      ModelPricingDTO pricing = ModelsDevPricingParser.parse(cost, issues);
      CatalogPricingValidator.validate(pricing);
      candidate.setPricing(pricing);
      candidate.setPricingSupported(true);
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
      candidate.setPricing(null);
      candidate.setPricingSupported(false);
    }
  }
}
