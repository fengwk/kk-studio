package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

/** Model mutations reject raw JSON that cannot be executed by the Harness runtime. */
public class AgentModelMutationFactoryTest {

  private static final String CAPABILITIES = "[\"TEXT\",\"TOOLS\"]";
  private static final String CONFIG =
      "{\"contextWindow\":32768,\"maxOutputTokens\":4096,"
          + "\"inputModalities\":[\"TEXT\"],"
          + "\"variants\":[{\"name\":\"default\",\"maxOutputTokens\":4096}],"
          + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"standard\","
          + "\"serviceTier\":\"default\",\"serviceTierMultiplier\":1,"
          + "\"version\":\"v1\",\"inputPerMillionTokens\":1,"
          + "\"outputPerMillionTokens\":2,\"cacheReadPerMillionTokens\":0.1,"
          + "\"cacheWritePerMillionTokens\":0.2,"
          + "\"cacheWriteLongPerMillionTokens\":0.3,"
          + "\"reasoningPerMillionTokens\":3}}";

  /** A create is accepted only when both raw fields form a complete executable config. */
  @Test
  public void shouldRequireExecutableModelConfiguration() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName("model");

    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, create));

    create.setCapabilitiesJson(CAPABILITIES);
    create.setConfigJson(CONFIG);
    AgentModel model = new AgentModel();
    model.setName("existing");
    factory.update(model, create);
    assertEquals("model", model.getName());
    assertEquals(CAPABILITIES, model.getCapabilitiesJson());
    assertEquals(CONFIG, model.getConfigJson());

    create.setCapabilitiesJson("{}");
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, create));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(0L, create));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, null));
  }

  /** A partial update preserves and revalidates the existing raw executable JSON. */
  @Test
  public void shouldPreserveExecutableConfigurationOnPartialUpdate() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setName("model");
    model.setCapabilitiesJson(CAPABILITIES);
    model.setConfigJson(CONFIG);
    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("updated");

    factory.update(model, update);

    assertEquals(CAPABILITIES, model.getCapabilitiesJson());
    assertEquals(CONFIG, model.getConfigJson());
    assertEquals("updated", model.getDescription());
  }

  private AgentModelMutationFactory factory() {
    ObjectMapper objectMapper = new ObjectMapper();
    return new AgentModelMutationFactory(
        new AgentEditableSupport(objectMapper), new AgentModelRuntimeConfigParser(objectMapper));
  }
}
