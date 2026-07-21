package fun.fengwk.kkstudio.core.agent.definition.service.converter;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;

/** Stored Agent configuration is either decoded losslessly or rejected. */
public class AgentDefinitionConverterTest {

  @Test
  public void shouldHandleNullAndRejectInvalidStoredConfiguration() {
    AgentDefinitionConverter converter =
        new AgentDefinitionConverter(new AgentDefinitionConfigCodec(new ObjectMapper()));
    assertNull(converter.convert(null));

    AgentDefinition invalid = new AgentDefinition();
    invalid.setId(1L);
    invalid.setModelId(2L);
    invalid.setConfigJson("not-json");
    assertThrows(IllegalStateException.class, () -> converter.convert(invalid));
    invalid.setConfigJson("null");
    assertThrows(IllegalStateException.class, () -> converter.convert(invalid));
    invalid.setConfigJson("{}");
    assertThrows(IllegalStateException.class, () -> converter.convert(invalid));
  }
}
