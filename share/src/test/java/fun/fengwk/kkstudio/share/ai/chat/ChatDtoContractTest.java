package fun.fengwk.kkstudio.share.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/** Chat wire DTO 契约测试：验证已删除 workspacePath 且暴露标准字段。 */
class ChatDtoContractTest {

  @Test
  void chatDtoExposesStandardFieldsAndDoesNotExposeWorkspacePath() throws Exception {
    Field agentName = ChatDTO.class.getDeclaredField("agentName");
    assertEquals(String.class, agentName.getType());
    assertThrows(NoSuchFieldException.class, () -> ChatDTO.class.getDeclaredField("workspacePath"));
  }
}
