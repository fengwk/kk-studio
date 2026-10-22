package fun.fengwk.kkstudio.share.ai.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

/** 定价 DTO 的 decimal 字符串 wire 契约与规则补丁空值语义。 */
class AgentModelPricingDtoJsonTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  /** decimal 始终以字符串往返；显式 reasoning:null 被保留，而未声明 reasoning 的补丁不会在编码时伪造 null。 */
  @Test
  void roundTripsDecimalStringsAndPreservesReasoningPresence() throws Exception {
    String json =
        "{\"base\":{\"input\":\"0.0000001\",\"output\":\"2.5\","
            + "\"cacheRead\":\"0\",\"cacheWrite\":\"3\","
            + "\"cacheWriteByTtl\":{\"300\":\"0.5\"}},"
            + "\"overrides\":[{\"id\":\"o1\",\"when\":{},\"rates\":{\"input\":\"1.25\","
            + "\"reasoning\":null}}],\"multipliers\":[]}";

    AgentModelPricingDTO decoded = mapper.readValue(json, AgentModelPricingDTO.class);
    assertEquals(new BigDecimal("0.0000001"), decoded.getBase().getInput());
    assertEquals(new BigDecimal("0.5"), decoded.getBase().getCacheWriteByTtl().get("300"));
    assertTrue(decoded.getOverrides().get(0).getRates().isReasoningSpecified());
    assertNull(decoded.getOverrides().get(0).getRates().getReasoning());

    String encoded = mapper.writeValueAsString(decoded);
    assertTrue(encoded.contains("\"input\":\"0.0000001\""), encoded);
    assertTrue(encoded.contains("\"reasoning\":null"), encoded);
    assertFalse(encoded.contains("\"input\":0.0000001"), encoded);
    AgentModelPricingDTO roundTripped = mapper.readValue(encoded, AgentModelPricingDTO.class);
    assertEquals(decoded.getBase(), roundTripped.getBase());
    assertTrue(roundTripped.getOverrides().get(0).getRates().isReasoningSpecified());

    AgentModelRatePatchDTO inputOnly = new AgentModelRatePatchDTO();
    inputOnly.setInput(new BigDecimal("7"));
    String inputOnlyJson = mapper.writeValueAsString(inputOnly);
    assertEquals("{\"input\":\"7\"}", inputOnlyJson);
  }

  /** JSON 数值和浮点近似不能冒充 decimal 字符串，普通价格与 TTL map 内容都必须拒绝。 */
  @Test
  void rejectsNumericPricesEverywhere() {
    assertThrows(
        JacksonException.class,
        () -> mapper.readValue("{\"input\":1.25}", AgentModelRateSetDTO.class));
    assertThrows(
        JacksonException.class,
        () ->
            mapper.readValue(
                "{\"input\":\"1\",\"cacheWriteByTtl\":{\"300\":0.5}}", AgentModelRateSetDTO.class));
    assertThrows(
        JacksonException.class,
        () -> mapper.readValue("{\"reasoning\":0}", AgentModelRatePatchDTO.class));
  }

  /** 显式 reasoning:null 与数值 0 是不同语义；0 必须是字符串 "0" 才能表示独立零价。 */
  @Test
  void differentiatesNullFromExplicitZero() throws Exception {
    AgentModelRatePatchDTO zero =
        mapper.readValue("{\"reasoning\":\"0\"}", AgentModelRatePatchDTO.class);
    assertTrue(zero.isReasoningSpecified());
    assertEquals(BigDecimal.ZERO, zero.getReasoning());
  }
}
