package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import org.junit.jupiter.api.Test;

/** Model mutation validation keeps schema JSON fields structurally valid. */
public class AgentModelMutationFactoryTest {

  @Test
  public void shouldDefaultAndValidateModelConfiguration() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName("model");
    assertEquals("[]", factory.newModel(2L, create).getCapabilitiesJson());
    assertEquals("{}", factory.newModel(2L, create).getConfigJson());

    create.setCapabilitiesJson("{}");
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, create));
    create.setCapabilitiesJson("[]");
    create.setConfigJson("[]");
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, create));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(0L, create));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(2L, null));
  }

  private AgentModelMutationFactory factory() {
    return new AgentModelMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
