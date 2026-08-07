package fun.fengwk.kkstudio.share.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/** Chat wire DTO 契约：environmentName 是 required-nullable 字段（@JsonInclude(ALWAYS) 显式发射 null）。 */
class ChatDtoContractTest {

  @Test
  void environmentNameIsRequiredNullableWithAlwaysInclude() throws Exception {
    Field environmentName = ChatDTO.class.getDeclaredField("environmentName");
    JsonInclude include = environmentName.getAnnotation(JsonInclude.class);
    assertEquals(JsonInclude.Include.ALWAYS, include.value());
  }

  @Test
  void selectedEnvironmentNameIsExposedAsPlainString() throws Exception {
    Field environmentName = ChatDTO.class.getDeclaredField("environmentName");
    assertEquals(String.class, environmentName.getType());
  }
}
