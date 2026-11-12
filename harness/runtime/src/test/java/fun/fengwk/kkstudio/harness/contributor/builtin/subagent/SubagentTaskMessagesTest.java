package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class SubagentTaskMessagesTest {
  @Test
  void acceptedStatesThreadIdAndFinalResult() {
    // 测试意图：task 回执说明调用等待最终报告，显式给出 thread_id 和终态，并区分失败与部分结果。
    UUID threadId = new UUID(0L, 1L);
    String message = SubagentTaskMessages.accepted(threadId);
    assertEquals(
        "Task result. thread_id: " + threadId + ".", message.lines().findFirst().orElseThrow());
    assertTrue(message.contains(threadId.toString()), message);
    assertTrue(message.contains("waits for it to finish"), message);
    assertTrue(message.contains("final report"), message);
    assertTrue(message.contains("completed, failed, or cancelled"), message);
    assertTrue(message.contains("error and partial result separately"), message);
    assertTrue(message.contains("thread_id " + threadId + ", subagent_type, and prompt"), message);
    assertThrows(NullPointerException.class, () -> SubagentTaskMessages.accepted(null));
  }

  @Test
  void replacedContinuationExplainsLatestPromptResult() {
    // 测试意图：忙碌子线程排队续接 prompt 时，结果只对应最新一次 prompt。
    UUID threadId = new UUID(0L, 2L);
    String message = SubagentTaskMessages.accepted(threadId, true);
    assertTrue(message.contains("supersedes earlier queued prompts"), message);
    assertTrue(message.contains("only the latest prompt receives a result"), message);
    assertEquals(
        "Task result. thread_id: " + threadId + ".", message.lines().findFirst().orElseThrow());
  }
}
