package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

/** 验证 Environment 相关 DTO 的序列化、反序列化以及未知字段严格校验契约。 */
class EnvironmentDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 无连接时仍显式给出 null 截止时间，客户端不以缺失字段猜测连接状态。 */
  @Test
  void offlineCardKeepsNullStatusDeadlineUnderNonNullWirePolicy() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setStatus("OFFLINE");
    ObjectMapper mapper = MAPPER.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    JsonNode json = mapper.readTree(mapper.writeValueAsString(card));
    assertTrue(json.has("statusExpiresAt"));
    assertTrue(json.get("statusExpiresAt").isNull());
  }

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

  /** 意图：验证 EnvironmentCardDTO 用 Daemon 进程用户与 HOME 替换旧 rootPath（后者不再是 cwd 或默认 workdir）。 */
  @Test
  void environmentCardDtoExposesHostUserAndHomeInsteadOfRootPath() throws Exception {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("rootPath"));
    assertEquals(String.class, EnvironmentCardDTO.class.getDeclaredField("userName").getType());
    assertEquals(
        String.class, EnvironmentCardDTO.class.getDeclaredField("homeDirectory").getType());
  }

  /** 意图：验证 Card 不再携带历史事件；完整事件列表是有界的四项事实，只在 events 端点暴露。 */
  @Test
  void environmentCardCarriesNoEventAndEventsStayBoundedFacts() throws Exception {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("lastEvent"));
    assertThrows(
        NoSuchMethodException.class, () -> EnvironmentCardDTO.class.getMethod("getLastEvent"));
    assertThrows(
        NoSuchMethodException.class,
        () -> EnvironmentCardDTO.class.getMethod("setLastEvent", EnvironmentEventDTO.class));
    assertEquals(
        List.of("time", "level", "type", "message"),
        List.of(EnvironmentEventDTO.class.getDeclaredFields()).stream()
            .map(Field::getName)
            .toList());

    // 事件时间在 wire 上是 Instant（Web 层按数值时间戳序列化）。
    assertEquals(Instant.class, EnvironmentEventDTO.class.getDeclaredField("time").getType());
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
