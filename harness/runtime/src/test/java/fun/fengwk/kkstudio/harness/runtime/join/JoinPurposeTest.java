package fun.fengwk.kkstudio.harness.runtime.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** JoinPurpose 的持久化/wire 名称往返与未知值 fail-closed 测试。 */
class JoinPurposeTest {

  @Test
  void wireNamesRoundTrip() {
    // 测试意图：TASK/COMPACTION 的 wire 名称稳定，且可按持久化名称往返解析。
    assertEquals("task", JoinPurpose.TASK.wireName());
    assertEquals("compaction", JoinPurpose.COMPACTION.wireName());
    assertEquals(JoinPurpose.TASK, JoinPurpose.fromWireName("task"));
    assertEquals(JoinPurpose.COMPACTION, JoinPurpose.fromWireName("compaction"));
  }

  @Test
  void unknownWireNameFailsClosed() {
    // 测试意图：未知或缺失名称必须 fail-closed，绝不静默回退成 task。
    assertThrows(IllegalArgumentException.class, () -> JoinPurpose.fromWireName("unknown"));
    assertThrows(IllegalArgumentException.class, () -> JoinPurpose.fromWireName(null));
  }
}
