package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;

import java.math.BigDecimal;

class HarnessJsonTest {

  /** 诊断解析允许重复键并保留精度，但仍沿用 Jackson 的尾随 token 限制。 */
  @Test
  void lenientDecimalIsIsolatedFromStrictReaders() {
    String duplicate = "{\"n\":1,\"n\":1e-324}";
    String trailing = "{\"n\":1e-324} {}";
    assertEquals(
        new BigDecimal("1e-324"),
        HarnessJson.LENIENT_DECIMAL.readTree(duplicate).get("n").decimalValue());
    assertThrows(JacksonException.class, () -> HarnessJson.LENIENT_DECIMAL.readTree(trailing));
    for (String json : new String[] {duplicate, trailing}) {
      assertThrows(JacksonException.class, () -> HarnessJson.STRICT.readTree(json));
      assertThrows(JacksonException.class, () -> HarnessJson.STRICT_DECIMAL.readTree(json));
    }
    assertFalse(HarnessJson.STRICT.readTree("1.25").isBigDecimal());
  }

  /** 自定义 factory 的输入限制与共享严格十进制配置应同时生效。 */
  @Test
  void strictDecimalKeepsFactoryLimits() {
    var mapper =
        HarnessJson.strictDecimal(
            JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(1).build())
                .build());
    assertEquals(new BigDecimal("1e-324"), mapper.readTree("[1e-324]").get(0).decimalValue());
    assertThrows(StreamConstraintsException.class, () -> mapper.readTree("[[]]"));
    assertThrows(JacksonException.class, () -> mapper.readTree("{} {}"));
  }
}
