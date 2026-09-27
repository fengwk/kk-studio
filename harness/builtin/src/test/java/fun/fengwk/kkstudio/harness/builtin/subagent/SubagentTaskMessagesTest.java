package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class SubagentTaskMessagesTest {
  @Test
  void acceptedContainsOnlyStableChildIdentity() {
    // 测试意图：即时 tool_result 只表示接受，不泄漏 prompt 或伪装完成结果。
    UUID threadId = new UUID(0L, 1L);
    assertEquals(
        "{\"thread_id\":\"" + threadId + "\",\"status\":\"accepted\"}",
        SubagentTaskMessages.accepted(threadId));
    assertThrows(NullPointerException.class, () -> SubagentTaskMessages.accepted(null));
  }
}
