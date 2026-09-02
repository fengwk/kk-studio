package fun.fengwk.kkstudio.share.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/** Chat wire DTO 契约：workspacePath 是 required-nullable 字段（@JsonInclude(ALWAYS) 显式发射 null）。 */
class ChatDtoContractTest {

  @Test
  void workspacePathIsRequiredNullableWithAlwaysInclude() throws Exception {
    Field workspacePath = ChatDTO.class.getDeclaredField("workspacePath");
    JsonInclude include = workspacePath.getAnnotation(JsonInclude.class);
    assertEquals(JsonInclude.Include.ALWAYS, include.value());
  }

  @Test
  void selectedWorkspacePathIsExposedAsString() throws Exception {
    Field workspacePath = ChatDTO.class.getDeclaredField("workspacePath");
    assertEquals(String.class, workspacePath.getType());
  }
}
