package fun.fengwk.kkstudio.share.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;

import java.lang.reflect.Field;

/** Chat wire DTO 契约：environment 是 required-nullable binding 对象（@JsonInclude(ALWAYS) 显式发射 null）。 */
class ChatDtoContractTest {

  @Test
  void environmentIsRequiredNullableWithAlwaysInclude() throws Exception {
    Field environment = ChatDTO.class.getDeclaredField("environment");
    JsonInclude include = environment.getAnnotation(JsonInclude.class);
    assertEquals(JsonInclude.Include.ALWAYS, include.value());
  }

  @Test
  void selectedEnvironmentIsExposedAsBindingObject() throws Exception {
    Field environment = ChatDTO.class.getDeclaredField("environment");
    assertEquals(EnvironmentBindingDTO.class, environment.getType());
  }
}
