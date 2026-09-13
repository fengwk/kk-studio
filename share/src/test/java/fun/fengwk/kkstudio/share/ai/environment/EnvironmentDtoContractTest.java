package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

/** 验证 Environment 相关 DTO 的序列化、反序列化以及未知字段严格校验契约。 */
class EnvironmentDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 意图：验证 EnvironmentCardDTO 已彻底移除 flat skills 字段以及对应的 getter/setter 访问器契约。 */
  @Test
  void environmentCardDtoHasNoSkillsFieldOrAccessors() {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("skills"));
    assertThrows(
        NoSuchMethodException.class, () -> EnvironmentCardDTO.class.getMethod("getSkills"));
    assertThrows(
        NoSuchMethodException.class,
        () -> EnvironmentCardDTO.class.getMethod("setSkills", List.class));
  }

  /** 意图：验证 EnvironmentUpdateDTO 能正确反序列化 name 和 expectedVersion，且在携带未知字段时报错。 */
  @Test
  void environmentUpdateDtoAcceptsNameAndExpectedVersionAndRejectsUnknown() throws Exception {
    String json = "{\"name\":\"test-env\",\"expectedVersion\":\"3\"}";
    EnvironmentUpdateDTO dto = MAPPER.readValue(json, EnvironmentUpdateDTO.class);
    assertEquals("test-env", dto.getName());
    assertEquals("3", dto.getExpectedVersion());

    String badJson = "{\"name\":\"test-env\",\"expectedVersion\":\"3\",\"unknown\":123}";
    assertThrows(Exception.class, () -> MAPPER.readValue(badJson, EnvironmentUpdateDTO.class));
  }

  /** 意图：验证 EnvironmentRotateTokenDTO 能正确反序列化 expectedVersion，且在携带未知字段时报错。 */
  @Test
  void environmentRotateTokenDtoAcceptsExpectedVersionAndRejectsUnknown() throws Exception {
    String json = "{\"expectedVersion\":\"5\"}";
    EnvironmentRotateTokenDTO dto = MAPPER.readValue(json, EnvironmentRotateTokenDTO.class);
    assertEquals("5", dto.getExpectedVersion());

    String badJson = "{\"expectedVersion\":\"5\",\"extra\":true}";
    assertThrows(Exception.class, () -> MAPPER.readValue(badJson, EnvironmentRotateTokenDTO.class));
  }
}
