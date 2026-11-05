package fun.fengwk.kkstudio.platform.harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;

import java.util.List;

/** Thread 约束的持久化协议测试：冻结字段严格校验。 */
class PostgresqlHarnessRowsTest {

  @Test
  void constraintsRoundTripWithoutChangingFrozenExecutionPolicy() {
    // 无上限与有上限都精确往返，字段顺序不影响 JSONB 读回。
    for (ThreadConstraints constraints :
        List.of(
            ThreadConstraints.NONE,
            new ThreadConstraints(1, true, false),
            new ThreadConstraints(Integer.MAX_VALUE, false, true))) {
      assertEquals(
          constraints,
          PostgresqlHarnessRows.decodeConstraints(
              PostgresqlHarnessRows.encodeConstraints(constraints)));
    }
    assertEquals(
        new ThreadConstraints(256, true, false),
        PostgresqlHarnessRows.decodeConstraints(
            "{\"allowAutoCompaction\":false,\"maxOutputTokens\":256,\"promptCacheDisabled\":true}"));
  }

  @Test
  void constraintsRejectMissingUnknownAndMalformedFields() {
    // 缺失/未知字段、重复键和尾随内容不能默认为更宽松的执行策略。
    assertThrows(
        IllegalArgumentException.class, () -> PostgresqlHarnessRows.decodeConstraints(null));
    for (String json :
        List.of(
            "",
            "null",
            "[]",
            "{}",
            "{\"promptCacheDisabled\":false,\"allowAutoCompaction\":true}",
            "{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}",
            "{\"maxOutputTokens\":null,\"promptCacheDisabled\":false}",
            "{\"maxOutputTokens\":null,\"promptCacheDisabled\":false,\"unknown\":true}",
            "{\"maxOutputTokens\":null,\"promptCacheDisabled\":false,\"allowAutoCompaction\":true,\"extra\":1}",
            "{\"maxOutputTokens\":null,\"promptCacheDisabled\":false,\"allowAutoCompaction\":true,\"allowAutoCompaction\":false}",
            "{\"maxOutputTokens\":null,\"promptCacheDisabled\":false,\"allowAutoCompaction\":true} {}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> PostgresqlHarnessRows.decodeConstraints(json),
          json);
    }
  }

  @Test
  void constraintsRejectCoercionsAndOutOfRangeBudgets() {
    // 不允许字符串/小数/溢出整数被 Jackson 的 intValue 或 booleanValue 静默转换。
    for (String cap : List.of("\"256\"", "1.5", "2147483648", "4294967297", "0", "-1", "true")) {
      String json =
          "{\"maxOutputTokens\":"
              + cap
              + ",\"promptCacheDisabled\":false,\"allowAutoCompaction\":true}";
      assertThrows(
          IllegalArgumentException.class, () -> PostgresqlHarnessRows.decodeConstraints(json), cap);
    }
    for (String value : List.of("null", "\"true\"", "1", "[]", "{}")) {
      for (String field : List.of("promptCacheDisabled", "allowAutoCompaction")) {
        String json =
            "{\"maxOutputTokens\":null,\"promptCacheDisabled\":false,\"allowAutoCompaction\":true}"
                .replace(
                    "\"" + field + "\":" + (field.equals("promptCacheDisabled") ? "false" : "true"),
                    "\"" + field + "\":" + value);
        assertThrows(
            IllegalArgumentException.class,
            () -> PostgresqlHarnessRows.decodeConstraints(json),
            json);
      }
    }
  }
}
