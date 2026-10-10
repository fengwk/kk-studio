package fun.fengwk.kkstudio.platform.catalog.definition.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

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
    invalid.setType(AgentDefinitionType.USER);
    invalid.setConfigJson("{\"tools\":[],\"skills\":[],\"subagents\":[]}");
    assertEquals("provider/model", converter.convert(invalid).getModel());
    assertEquals(AgentDefinitionType.USER, converter.convert(invalid).getType());

    // 未配置模型的内置 Agent：两列同为 null，DTO 显式投影 null 而不是构造非法 ModelRef。
    invalid.setType(AgentDefinitionType.BUILTIN);
    invalid.setModelProviderName(null);
    invalid.setModelName(null);
    AgentDefinitionDTO unconfigured = converter.convert(invalid);
    assertEquals(AgentDefinitionType.BUILTIN, unconfigured.getType());
    assertNull(unconfigured.getModel());
  }
}
