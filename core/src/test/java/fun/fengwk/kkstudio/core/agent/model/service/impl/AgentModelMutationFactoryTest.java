package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.model.AgentModelTestData;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

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
    model.setConfigJson(serialize(baseline));
    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("updated");

    factory.update(model, update);

    assertEquals(serialize(baseline), model.getConfigJson());
    assertEquals("updated", model.getDescription());
  }

  private static String serialize(AgentModelConfigDTO config) {
    try {
      return new ObjectMapper().writeValueAsString(config);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private AgentModelMutationFactory factory() {
    ObjectMapper objectMapper = new ObjectMapper();
    return new AgentModelMutationFactory(
        new AgentEditableSupport(objectMapper),
        new AgentModelRuntimeConfigParser(objectMapper),
        objectMapper);
  }
}
