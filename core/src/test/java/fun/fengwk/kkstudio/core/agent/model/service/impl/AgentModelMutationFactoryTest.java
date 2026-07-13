package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;

/** Model mutation validation keeps schema JSON fields structurally valid. */
public class AgentModelMutationFactoryTest {

  @Test
  public void shouldDefaultAndValidateModelJsonConfiguration() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName("model");
    assertEquals("[]", factory.newModel(1L, 2L, create).getCapabilitiesJson());

    create.setCapabilitiesJson("{}");
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(1L, 2L, create));
  }

  private AgentModelMutationFactory factory() {
    return new AgentModelMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
