package fun.fengwk.kkstudio.core.ai.catalog.definition.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;

/** 已存储的 Agent 配置要么无损解码，要么被拒绝。 */
public class AgentDefinitionConverterTest {

  @Test
  public void shouldHandleNullAndRejectInvalidStoredConfiguration() {
    AgentDefinitionConverter converter =
        new AgentDefinitionConverter(new AgentDefinitionConfigCodec(new ObjectMapper()));
    assertNull(converter.convert(null));

    AgentDefinition invalid = new AgentDefinition();
    invalid.setModelProviderName("provider");
    invalid.setModelName("model");
    invalid.setConfigJson("not-json");
    assertThrows(IllegalStateException.class, () -> converter.convert(invalid));
    invalid.setConfigJson("null");
    assertThrows(IllegalStateException.class, () -> converter.convert(invalid));
    invalid.setConfigJson("{}");
    assertThrows(IllegalStateException.class, () -> converter.convert(invalid));

    invalid.setName("agent");
    invalid.setVersion(0L);
    invalid.setConfigJson("{\"tools\":[],\"skills\":[]}");
    assertEquals("provider/model", converter.convert(invalid).getModel());
  }
}
