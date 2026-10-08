package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class SubagentTaskMessagesTest {
  @Test
  void acceptedStatesThreadIdAndAsyncContinuation() {
    // 测试意图：即时 tool_result 是英文自然语言接受回执，显式给出 thread_id 并说明异步/继续方式，不泄漏 prompt 或伪装完成结果。
    UUID threadId = new UUID(0L, 1L);
    String message = SubagentTaskMessages.accepted(threadId);
    assertEquals(
        "Task accepted. thread_id: " + threadId + ".", message.lines().findFirst().orElseThrow());
    assertTrue(message.contains(threadId.toString()), message);
    assertTrue(message.contains("asynchronously"), message);
    assertTrue(message.contains("fail") && message.contains("cancelled"), message);
    assertTrue(message.contains("yield"), message);
    assertTrue(message.contains("thread_id " + threadId + ", subagent_type, and prompt"), message);
    assertThrows(NullPointerException.class, () -> SubagentTaskMessages.accepted(null));
  }

  @Test
  void replacedAcceptanceExplainsSingleConsolidatedResult() {
    // 测试意图：busy follow-up 取代旧 pending wait 时，回执必须说明只会有最新一次的一份汇总结果。
    UUID threadId = new UUID(0L, 2L);
    String message = SubagentTaskMessages.accepted(threadId, true);
    assertTrue(message.contains("replaces the previous pending wait"), message);
    assertTrue(message.contains("only one consolidated result"), message);
    assertEquals(
        "Task accepted. thread_id: " + threadId + ".", message.lines().findFirst().orElseThrow());
  }
}
