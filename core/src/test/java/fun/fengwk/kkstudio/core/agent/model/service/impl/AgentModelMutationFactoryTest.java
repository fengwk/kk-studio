package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import fun.fengwk.kkstudio.core.agent.model.AgentModelTestData;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
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

    assertThrows(AiValidationException.class, () -> factory.newModel(2L, create));
    AgentModel existing = new AgentModel();
    existing.setName("existing");
    assertThrows(
        AiValidationException.class, () -> factory.update(existing, new AgentModelUpdateDTO()));

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
    assertThrows(AiValidationException.class, () -> factory.newModel(2L, create));
    assertThrows(AiValidationException.class, () -> factory.newModel(0L, create));
    assertThrows(AiValidationException.class, () -> factory.newModel(2L, null));
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
    update.setName("model");
    update.setDescription("renamed");
    update.setConfig(baseline);
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
    update.setName("model");
    update.setDescription("renamed");
    update.setConfig(baseline);
    factory.update(model, update);

    AgentModelConfigDTO persisted =
        new AgentModelRuntimeConfigParser(new ObjectMapper()).decode(model.getConfigJson());
    assertEquals(
        List.of(AgentModelInputModality.TEXT), persisted.getAbilities().getInputModalities());
  }

  @Test
  public void shouldEnforceModelSchemaStringLimitsAfterNormalization() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO accepted = create("n".repeat(128), "d".repeat(512));
    AgentModel persisted = factory.newModel(2L, accepted);
    assertEquals("n".repeat(128), persisted.getName());
    assertEquals("d".repeat(512), persisted.getDescription());

    assertThrows(
        AiValidationException.class, () -> factory.newModel(2L, create("n".repeat(129), null)));
    assertThrows(
        AiValidationException.class, () -> factory.newModel(2L, create("model", "d".repeat(513))));
  }

  private static AgentModelCreateDTO create(String name, String description) {
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName(name);
    create.setDescription(description);
    create.setConfig(validConfig());
    return create;
  }

  private static String serialize(AgentModelConfigDTO config) {
    try {
      return new ObjectMapper().writeValueAsString(config);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private AgentModelMutationFactory factory() {
    PostgresqlSequenceIdGenerator idGenerator = Mockito.mock(PostgresqlSequenceIdGenerator.class);
    when(idGenerator.next()).thenReturn(202L);
    return new AgentModelMutationFactory(
        new AgentEditableSupport(new ObjectMapper()),
        new AgentModelRuntimeConfigParser(new ObjectMapper()),
        idGenerator);
  }
}
