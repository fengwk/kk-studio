package fun.fengwk.kkstudio.harness.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;

import java.util.List;
import java.util.UUID;

/**
 * Thread 约束与 {@code cancelled_inputs} 列的持久化协议测试：冻结字段严格校验，自由 JSON 数值按 BigDecimal 搬运。
 *
 * <p>{@code SET_CONTRIBUTOR_STATE} 的 {@code state.data} 是唯一携带自由数值树的 command payload，因此用它锁定精度。
 */
class PostgresqlHarnessRowsTest {

  private static final String DATA_JSON =
      "{\"big\":1E+309,\"tiny\":1E-324,\"exact\":0.1234567890123456789012345678}";

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

  @Test
  void cancelledInputsPreserveFreeFormNumberPrecision() {
    CancelledThreadInput input =
        new CancelledThreadInput(
            1,
            UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            new SetContributorStateCommandPayload(
                new CustomEntryPayload("com.example.issue", "issue-scope", 1, DATA_JSON)));

    String encoded = PostgresqlHarnessRows.encodeCancelledInputs(List.of(input));

    assertTrue(encoded.contains("1E+309"), encoded);
    assertTrue(encoded.contains("1E-324"), encoded);
    assertFalse(encoded.contains("Infinity"), encoded);
    assertEquals(List.of(input), PostgresqlHarnessRows.decodeCancelledInputs(encoded));
    assertEquals(
        DATA_JSON, ((SetContributorStateCommandPayload) input.payload()).state().dataJson());
  }
}
