package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextModelId;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import org.springframework.stereotype.Component;

/**
 * AgentModelMutationFactory 负责 model 写路径的入参校验、标准化与实体组装。
 *
 * @author fengwk
 */
@Component
final class AgentModelMutationFactory {

  private static final String DEFAULT_VARIANT = "default";
  private static final String DEFAULT_VARIANTS_JSON = "[{\"name\":\"default\"}]";

  private final AgentEditableSupport editableSupport;

  AgentModelMutationFactory(AgentEditableSupport editableSupport) {
    if (editableSupport == null) {
      throw new IllegalArgumentException("editableSupport must not be null");
    }
    this.editableSupport = editableSupport;
  }

  Mutation newCreateMutation(AgentModelCreateDTO createDTO) {
    validateEditable(createDTO, true);
    String providerName = editableSupport.trimToNull(createDTO.getProvider());
    if (providerName == null) {
      throw new IllegalArgumentException("agent model provider must not be blank");
    }
    return new Mutation(
        providerName,
        editableSupport.trimToNull(createDTO.getName()),
        editableSupport.trimToNull(createDTO.getDescription()),
        editableSupport.trimToNull(createDTO.getCapabilitiesJson()),
        editableSupport.trimToNull(createDTO.getLimitJson()),
        editableSupport.trimToNull(createDTO.getPricingJson()),
        editableSupport.firstNonBlank(createDTO.getDefaultVariant(), DEFAULT_VARIANT),
        editableSupport.firstNonBlank(createDTO.getVariantsJson(), DEFAULT_VARIANTS_JSON));
  }

  Mutation newUpdateMutation(String currentName, AgentModelUpdateDTO updateDTO) {
    requireNonBlank(currentName, "currentName");
    validateEditable(updateDTO, false);
    return new Mutation(
        null,
        editableSupport.firstNonBlank(updateDTO.getName(), currentName),
        editableSupport.trimToNull(updateDTO.getDescription()),
        editableSupport.trimToNull(updateDTO.getCapabilitiesJson()),
        editableSupport.trimToNull(updateDTO.getLimitJson()),
        editableSupport.trimToNull(updateDTO.getPricingJson()),
        editableSupport.firstNonBlank(updateDTO.getDefaultVariant(), DEFAULT_VARIANT),
        editableSupport.firstNonBlank(updateDTO.getVariantsJson(), DEFAULT_VARIANTS_JSON));
  }

  AgentModel newModel(long providerId, Mutation mutation) {
    if (providerId <= 0) {
      throw new IllegalArgumentException("providerId must be positive");
    }
    requireNonNull(mutation, "mutation");
    AgentModel model = new AgentModel();
    model.setId(nextModelId());
    model.setProviderId(providerId);
    apply(model, mutation);
    return model;
  }

  void apply(AgentModel model, Mutation mutation) {
    requireNonNull(model, "model");
    requireNonNull(mutation, "mutation");
    model.setName(mutation.name());
    model.setDescription(mutation.description());
    model.setCapabilitiesJson(mutation.capabilitiesJson());
    model.setLimitJson(mutation.limitJson());
    model.setPricingJson(mutation.pricingJson());
    model.setDefaultVariant(mutation.defaultVariant());
    model.setVariantsJson(mutation.variantsJson());
  }

  private void validateEditable(AgentModelEditablePropertiesDTO properties, boolean requireName) {
    if (properties == null) {
      throw new IllegalArgumentException("agent model body must not be null");
    }
    if (requireName && editableSupport.trimToNull(properties.getName()) == null) {
      throw new IllegalArgumentException("agent model name must not be blank");
    }
    editableSupport.validateJsonObjectOrNull(properties.getCapabilitiesJson(), "capabilitiesJson");
    editableSupport.validateJsonObjectOrNull(properties.getLimitJson(), "limitJson");
    editableSupport.validateJsonObjectOrNull(properties.getPricingJson(), "pricingJson");
    editableSupport.validateJsonArray(
        editableSupport.firstNonBlank(properties.getVariantsJson(), DEFAULT_VARIANTS_JSON),
        "variantsJson");
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
      String providerName,
      String name,
      String description,
      String capabilitiesJson,
      String limitJson,
      String pricingJson,
      String defaultVariant,
      String variantsJson) {}
}
