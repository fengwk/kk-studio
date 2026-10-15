package fun.fengwk.kkstudio.share.ai.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** 显式连接检查响应的 wire 契约：三个维度是封闭枚举，wire 只接受契约内的精确名称。 */
class PluginConnectionCheckDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void componentDimensionsAreClosedEnumsWithTheExactContractSet() {
    // 意图：维度取值集合是契约本身，既不加别名也不允许健康值之外的臆造形态。
    assertEquals(
        List.of("REACHABLE", "UNREACHABLE", "NOT_CHECKED", "INDETERMINATE"),
        Arrays.stream(PluginConnectionCheckDTO.Reachability.values()).map(Enum::name).toList());
    assertEquals(
        List.of("ACCEPTED", "REJECTED", "UNAVAILABLE", "NOT_CHECKED"),
        Arrays.stream(PluginConnectionCheckDTO.Authentication.values()).map(Enum::name).toList());
    assertEquals(
        List.of("UNKNOWN"),
        Arrays.stream(PluginConnectionCheckDTO.BrowserSession.values()).map(Enum::name).toList());

    assertEquals(PluginConnectionCheckDTO.Reachability.class, fieldType("reachability"));
    assertEquals(PluginConnectionCheckDTO.Authentication.class, fieldType("authentication"));
    assertEquals(PluginConnectionCheckDTO.BrowserSession.class, fieldType("browserSession"));
  }

  @Test
  void responseRoundTripsThroughTheRealJacksonWire() throws Exception {
    PluginConnectionCheckDTO dto = new PluginConnectionCheckDTO();
    dto.setPluginId("opencli");
    dto.setAppliedEpoch("2");
    dto.setAppliedRevision("2");
    dto.setCheckedAt(Instant.parse("2026-03-01T12:00:00Z"));
    dto.setReachability(PluginConnectionCheckDTO.Reachability.REACHABLE);
    dto.setAuthentication(PluginConnectionCheckDTO.Authentication.UNAVAILABLE);
    dto.setBrowserSession(PluginConnectionCheckDTO.BrowserSession.UNKNOWN);

    String json = MAPPER.writeValueAsString(dto);
    assertTrue(json.contains("\"reachability\":\"REACHABLE\""), json);
    assertTrue(json.contains("\"authentication\":\"UNAVAILABLE\""), json);
    assertTrue(json.contains("\"browserSession\":\"UNKNOWN\""), json);

    PluginConnectionCheckDTO decoded = MAPPER.readValue(json, PluginConnectionCheckDTO.class);
    assertEquals(PluginConnectionCheckDTO.Reachability.REACHABLE, decoded.getReachability());
    assertEquals(PluginConnectionCheckDTO.Authentication.UNAVAILABLE, decoded.getAuthentication());
    assertEquals(PluginConnectionCheckDTO.BrowserSession.UNKNOWN, decoded.getBrowserSession());
    assertEquals(Instant.parse("2026-03-01T12:00:00Z"), decoded.getCheckedAt());
  }

  @Test
  void outOfContractDimensionValuesAreRejected() {
    // 意图：越界取值 fail closed，绝不静默降级成 null 或健康值。
    assertThrows(
        Exception.class,
        () -> MAPPER.readValue("{\"reachability\":\"HEALTHY\"}", PluginConnectionCheckDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue("{\"authentication\":\"CONNECTED\"}", PluginConnectionCheckDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue("{\"browserSession\":\"LOGGED_IN\"}", PluginConnectionCheckDTO.class));
  }

  @Test
  void responseDeclaresOnlyCoordinatesAndTypedDimensions() throws Exception {
    // 意图：响应里物理上不存在自由文本字段（异常消息、远端 URI/正文、凭据）可供回显。
    assertEquals(
        List.of(
            "appliedEpoch",
            "appliedRevision",
            "authentication",
            "browserSession",
            "checkedAt",
            "pluginId",
            "reachability"),
        Arrays.stream(PluginConnectionCheckDTO.class.getDeclaredFields())
            .map(Field::getName)
            .sorted()
            .toList());
  }

  private static Class<?> fieldType(String name) {
    try {
      return PluginConnectionCheckDTO.class.getDeclaredField(name).getType();
    } catch (NoSuchFieldException error) {
      throw new AssertionError("connection check DTO must declare " + name, error);
    }
  }
}
