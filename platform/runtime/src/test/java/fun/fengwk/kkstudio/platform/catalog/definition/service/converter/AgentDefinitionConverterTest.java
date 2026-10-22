package fun.fengwk.kkstudio.platform.catalog.definition.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

/** 已存储的 Agent 配置要么无损解码，要么被拒绝。 */
public class AgentDefinitionConverterTest {

  @Test
  public void shouldHandleNullAndRejectInvalidStoredConfiguration() {
    AgentDefinitionConverter converter =
        new AgentDefinitionConverter(new AgentDefinitionConfigCodec(JsonMapper.builder().build()));
    assertNull(converter.convert(null));

    AgentDefinition invalid = new AgentDefinition();
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
    assertEquals("model", converter.convert(invalid).getModel());
    assertEquals(AgentDefinitionType.USER, converter.convert(invalid).getType());

    // 未配置模型的内置 Agent：model 列为 null，DTO 显式投影 null。
    invalid.setType(AgentDefinitionType.BUILTIN);
    invalid.setModelName(null);
    AgentDefinitionDTO unconfigured = converter.convert(invalid);
    assertEquals(AgentDefinitionType.BUILTIN, unconfigured.getType());
    assertNull(unconfigured.getModel());
  }
}
