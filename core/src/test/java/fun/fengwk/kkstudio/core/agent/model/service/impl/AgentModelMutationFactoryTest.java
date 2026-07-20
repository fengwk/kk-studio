package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.model.AgentModelTestData;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelInputModality;
import fun.fengwk.kkstudio.share.model.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

import java.math.BigDecimal;
import java.util.List;

/** Model mutations reject raw JSON that cannot be executed by the Harness runtime. */
public class AgentModelMutationFactoryTest {

  private static AgentModelConfigDTO validConfig() {
    return AgentModelTestData.buildConfig(
        32768, 4096, true, false, "standard", "v1", List.of("default"), "default");
  }

  /** A create is accepted only when the structured config is a complete executable config. */
  @Test
  public void shouldRequireExecutableModelConfiguration() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName("model");

    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, create));

    AgentModelConfigDTO config = validConfig();
    create.setConfig(config);
    AgentModel model = new AgentModel();
    model.setName("existing");
    factory.update(model, create);
    assertEquals("model", model.getName());
    assertEquals(serialize(config), model.getConfigJson());

    AgentModelConfigDTO invalid =
        AgentModelTestData.buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of(), "missing");
    create.setConfig(invalid);
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, create));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(0L, create));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, null));
  }

  /** A partial update preserves and revalidates the existing executable config. */
  @Test
  public void shouldPreserveExecutableConfigurationOnPartialUpdate() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setName("model");
    AgentModelConfigDTO baseline = validConfig();
    // Pre-roundtrip so the baseline matches the canonical encoded form.
    String canonicalJson =
        new AgentModelRuntimeConfigParser(new ObjectMapper())
            .encode(
                new AgentModelRuntimeConfigParser(new ObjectMapper()).decode(serialize(baseline)));
    model.setConfigJson(canonicalJson);
    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("updated");

    factory.update(model, update);

    assertEquals(canonicalJson, model.getConfigJson());
    assertEquals("updated", model.getDescription());
  }

  /** Even when persisted JSON has null optional variant fields, encoded output normalizes them. */
  @Test
  public void shouldNormalizePersistedConfigOnUpdate() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setName("model");
    AgentModelConfigDTO baseline = validConfig();
    // Force persisted JSON to omit optional variant fields (stopSequences null).
    String withoutStopSequences = serialize(baseline).replace(",\"stopSequences\":null", "");
    model.setConfigJson(withoutStopSequences);

    factory.update(model, new AgentModelUpdateDTO());

    AgentModelConfigDTO persisted =
        new AgentModelRuntimeConfigParser(new ObjectMapper()).decode(model.getConfigJson());
    assertEquals(List.of(), persisted.getVariants().get(0).getStopSequences());
  }

  /**
   * Edit must round-trip the pricing metadata (currency/pricingTier/serviceTier/multiplier/version)
   * instead of clobbering it with fixed defaults.
   */
  @Test
  public void shouldPreservePricingMetadataAcrossEdit() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setName("model");
    AgentModelConfigDTO baseline = validConfig();
    AgentModelPricingDTO pricing = baseline.getPricing();
    pricing.setCurrency("EUR");
    pricing.setPricingTier("batch");
    pricing.setServiceTier("priority");
    pricing.setServiceTierMultiplier(new BigDecimal("1.5"));
    pricing.setVersion("2026-08-01");
    pricing.setInputPerMillionTokens(new BigDecimal("7"));
    pricing.setOutputPerMillionTokens(new BigDecimal("9"));
    pricing.setCacheReadPerMillionTokens(new BigDecimal("1"));
    pricing.setCacheWritePerMillionTokens(new BigDecimal("2"));
    pricing.setCacheWriteLongPerMillionTokens(new BigDecimal("3"));
    pricing.setReasoningPerMillionTokens(new BigDecimal("4"));
    model.setConfigJson(serialize(baseline));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("renamed");
    factory.update(model, update);

    AgentModelConfigDTO persisted =
        new AgentModelRuntimeConfigParser(new ObjectMapper()).decode(model.getConfigJson());
    assertNotNull(persisted.getPricing());
    assertEquals("EUR", persisted.getPricing().getCurrency());
    assertEquals("batch", persisted.getPricing().getPricingTier());
    assertEquals("priority", persisted.getPricing().getServiceTier());
    assertEquals(new BigDecimal("1.5"), persisted.getPricing().getServiceTierMultiplier());
    assertEquals("2026-08-01", persisted.getPricing().getVersion());
    assertEquals(new BigDecimal("7"), persisted.getPricing().getInputPerMillionTokens());
    assertEquals(new BigDecimal("9"), persisted.getPricing().getOutputPerMillionTokens());
    assertEquals(new BigDecimal("1"), persisted.getPricing().getCacheReadPerMillionTokens());
    assertEquals(new BigDecimal("2"), persisted.getPricing().getCacheWritePerMillionTokens());
    assertEquals(new BigDecimal("3"), persisted.getPricing().getCacheWriteLongPerMillionTokens());
    assertEquals(new BigDecimal("4"), persisted.getPricing().getReasoningPerMillionTokens());
  }

  /**
   * Edit must keep the existing default input modality (TEXT-only by default) instead of expanding
   * it to every supported modality.
   */
  @Test
  public void shouldPreserveInputModalitiesOnEdit() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setName("model");
    AgentModelConfigDTO baseline = validConfig();
    baseline.getAbilities().setInputModalities(List.of(AgentModelInputModality.TEXT));
    model.setConfigJson(serialize(baseline));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("renamed");
    factory.update(model, update);

    AgentModelConfigDTO persisted =
        new AgentModelRuntimeConfigParser(new ObjectMapper()).decode(model.getConfigJson());
    assertEquals(
        List.of(AgentModelInputModality.TEXT), persisted.getAbilities().getInputModalities());
  }

  private static String serialize(AgentModelConfigDTO config) {
    try {
      return new ObjectMapper().writeValueAsString(config);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private AgentModelMutationFactory factory() {
    return new AgentModelMutationFactory(new AgentModelRuntimeConfigParser(new ObjectMapper()));
  }
}
