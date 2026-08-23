package fun.fengwk.kkstudio.platform.ai.catalog.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.ai.catalog.model.AgentModelTestData;
import fun.fengwk.kkstudio.platform.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.ai.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

import java.math.BigDecimal;
import java.util.List;

/** 模型变更拒绝 Harness runtime 无法执行的配置。 */
public class AgentModelMutationFactoryTest {

  private static AgentModelConfigDTO validConfig() {
    return AgentModelTestData.buildConfig(
        32768, 4096, true, false, "standard", "v1", List.of("default"), "default");
  }

  @Test
  public void shouldRequireExecutableModelConfigurationAndKeepIdentity() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO create = new AgentModelCreateDTO();

    assertThrows(AiValidationException.class, () -> factory.newModel("provider", "model", create));
    AgentModel existing = new AgentModel();
    existing.setName("existing");
    assertThrows(
        AiValidationException.class, () -> factory.update(existing, new AgentModelUpdateDTO()));

    create.setConfig(validConfig());
    AgentModel model = factory.newModel("provider", "model", create);
    assertEquals("provider", model.getProviderName());
    assertEquals("model", model.getName());
    factory.update(model, create);
    assertEquals("model", model.getName());
    assertEquals("provider", model.getProviderName());

    AgentModelConfigDTO invalid =
        AgentModelTestData.buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of(), "missing");
    create.setConfig(invalid);
    assertThrows(AiValidationException.class, () -> factory.newModel("provider", "model", create));
    assertThrows(AiValidationException.class, () -> factory.newModel(" ", "model", create));
    assertThrows(AiValidationException.class, () -> factory.newModel("provider", "model", null));
  }

  @Test
  public void shouldPreservePricingMetadataAcrossEdit() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setProviderName("provider");
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

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("updated");
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

  @Test
  public void shouldEnforceModelSchemaStringLimits() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO accepted = create("n".repeat(128), "d".repeat(512));
    AgentModel persisted = factory.newModel("provider", accepted.getName(), accepted);
    assertEquals("n".repeat(128), persisted.getName());
    assertEquals("d".repeat(512), persisted.getDescription());

    assertThrows(
        AiValidationException.class,
        () -> factory.newModel("provider", "\u2003model\u2003", create("model", null)));
    assertThrows(
        AiValidationException.class,
        () -> factory.newModel("provider", "n".repeat(129), create("n".repeat(129), null)));
    assertThrows(
        AiValidationException.class,
        () -> factory.newModel("provider", "model", create("model", "d".repeat(513))));
  }

  private static AgentModelCreateDTO create(String name, String description) {
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName(name);
    create.setProviderName("provider");
    create.setDescription(description);
    create.setConfig(validConfig());
    return create;
  }

  private AgentModelMutationFactory factory() {
    ObjectMapper objectMapper = new ObjectMapper();
    return new AgentModelMutationFactory(
        new AgentEditableSupport(objectMapper), new AgentModelRuntimeConfigParser(objectMapper));
  }
}
