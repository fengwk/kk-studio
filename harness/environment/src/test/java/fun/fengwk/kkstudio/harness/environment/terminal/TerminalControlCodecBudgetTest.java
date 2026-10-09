package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.ViewUpdate;

import java.nio.charset.StandardCharsets;

/** 控制协议的 8 MiB 载体预算证明：含控制事件根与 route wrapper，嵌入既有画面更新。 */
class TerminalControlCodecBudgetTest {

  private static final TerminalControlCodec CODEC = new TerminalControlCodec();

  @Test
  void maximalViewUpdateEventFitsBudgetAndRoundtrips() {
    TerminalEvent event =
        new TerminalEvent(
            TerminalControlSamples.REQUEST,
            TerminalControlSamples.ENVIRONMENT,
            TerminalControlSamples.VIEWER,
            TerminalControlSamples.identity(),
            new ViewUpdate(TerminalViewSamples.maximalReset()));
    String json = CODEC.encodeEvent(event);
    int bytes = json.getBytes(StandardCharsets.UTF_8).length;
    // 最大允许画面加控制 event 根仍必须落在 8 MiB 载体预算以内。
    assertTrue(bytes < TerminalLimits.MAX_MESSAGE_BYTES, "encoded bytes=" + bytes);
    assertTrue(bytes > 7356173, "encoded bytes=" + bytes);

    assertEquals(event, CODEC.decodeEvent(json));
    assertEquals(json, CODEC.encodeEvent(CODEC.decodeEvent(json)));
  }

  @Test
  void rejectsOversizedMessageBeforeDecoding() {
    String oversized = "a".repeat(TerminalLimits.MAX_MESSAGE_BYTES + 1);
    assertThrows(
        TerminalControlCodec.TerminalControlException.class, () -> CODEC.decodeEvent(oversized));
    assertThrows(
        TerminalControlCodec.TerminalControlException.class, () -> CODEC.decodeCommand(oversized));
  }
}
