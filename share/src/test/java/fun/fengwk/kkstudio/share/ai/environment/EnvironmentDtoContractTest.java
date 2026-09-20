package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
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

  /** 意图：验证 EnvironmentCardDTO 用 Daemon 进程用户与 HOME 替换旧 rootPath（后者不再是 cwd 或默认 workdir）。 */
  @Test
  void environmentCardDtoExposesHostUserAndHomeInsteadOfRootPath() throws Exception {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("rootPath"));
    assertEquals(String.class, EnvironmentCardDTO.class.getDeclaredField("userName").getType());
    assertEquals(
        String.class, EnvironmentCardDTO.class.getDeclaredField("homeDirectory").getType());
  }

  /** 意图：验证 Card 只携带最近一条 WARN/ERROR 事件，而完整事件列表是有界的四项事实。 */
  @Test
  void environmentCardCarriesLatestEventAndEventsStayBoundedFacts() throws Exception {
    assertEquals(
        EnvironmentEventDTO.class,
        EnvironmentCardDTO.class.getDeclaredField("lastEvent").getType());
    assertEquals(
        List.of("time", "level", "type", "message"),
        List.of(EnvironmentEventDTO.class.getDeclaredFields()).stream()
            .map(Field::getName)
            .toList());

    // 事件时间在 wire 上是 Instant（Web 层按数值时间戳序列化），序列化往返只覆盖文本事实。
    assertEquals(Instant.class, EnvironmentEventDTO.class.getDeclaredField("time").getType());
    EnvironmentEventDTO event = new EnvironmentEventDTO();
    event.setLevel("ERROR");
    event.setType("SKILL_SYNC_FAILED");
    event.setMessage("fetch failed");
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setLastEvent(event);
    assertEquals(
        "SKILL_SYNC_FAILED",
        MAPPER
            .readValue(MAPPER.writeValueAsString(card), EnvironmentCardDTO.class)
            .getLastEvent()
            .getType());
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
