package fun.fengwk.kkstudio.share.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/** Chat wire DTO 契约测试：验证已删除 workspacePath 且暴露标准字段与环境默认值语义。 */
class ChatDtoContractTest {

  @Test
  void chatDtoExposesStandardFieldsAndDoesNotExposeWorkspacePath() throws Exception {
    Field agentName = ChatDTO.class.getDeclaredField("agentName");
    assertEquals(String.class, agentName.getType());
    Field environmentName = ChatDTO.class.getDeclaredField("environmentName");
    assertEquals(String.class, environmentName.getType());
    assertThrows(NoSuchFieldException.class, () -> ChatDTO.class.getDeclaredField("workspacePath"));
  }

  /** environmentName 省略保留、显式 null 清空：JSON Setter 必须区分两者。 */
  @Test
  void updateDtoTracksEnvironmentNameProvidedSemantics() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    ChatUpdateDTO omitted = mapper.readValue("{\"expectedVersion\":\"1\"}", ChatUpdateDTO.class);
    assertFalse(omitted.isEnvironmentNameProvided());

    ChatUpdateDTO explicitNull =
        mapper.readValue(
            "{\"environmentName\":null,\"expectedVersion\":\"1\"}", ChatUpdateDTO.class);
    assertTrue(explicitNull.isEnvironmentNameProvided());
    assertNull(explicitNull.getEnvironmentName());

    ChatUpdateDTO explicitValue =
        mapper.readValue(
            "{\"environmentName\":\"env-a\",\"expectedVersion\":\"1\"}", ChatUpdateDTO.class);
    assertTrue(explicitValue.isEnvironmentNameProvided());
    assertEquals("env-a", explicitValue.getEnvironmentName());
  }

  /** 未知字段在 DTO 边界被拒绝，不会被静默忽略。 */
  @Test
  void rejectsUnknownFields() {
    assertThrows(IllegalArgumentException.class, () -> new ChatDTO().rejectUnknownField("x", 1));
    assertThrows(
        IllegalArgumentException.class, () -> new ChatCreateDTO().rejectUnknownField("x", 1));
    assertThrows(
        IllegalArgumentException.class, () -> new ChatUpdateDTO().rejectUnknownField("x", 1));
  }
}
