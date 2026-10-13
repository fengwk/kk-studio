package fun.fengwk.kkstudio.harness.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;

import java.util.List;
import java.util.UUID;

/**
 * {@code cancelled_inputs} 列的持久化协议测试：自由 JSON 数值按 BigDecimal 搬运，不因 jsonb 往返退化。
 *
 * <p>{@code SET_CONTRIBUTOR_STATE} 的 {@code state.data} 是唯一携带自由数值树的 command payload，因此用它锁定精度。
 */
class PostgresqlHarnessRowsTest {

  private static final String DATA_JSON =
      "{\"big\":1E+309,\"tiny\":1E-324,\"exact\":0.1234567890123456789012345678}";

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
