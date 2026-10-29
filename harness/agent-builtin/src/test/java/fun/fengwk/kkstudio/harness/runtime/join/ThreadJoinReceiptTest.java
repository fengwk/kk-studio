package fun.fengwk.kkstudio.harness.runtime.join;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** {@link ThreadJoinReceipt} 不可变数据记录与构造约束测试。 */
class ThreadJoinReceiptTest {

  private static final UUID INVOCATION_ID = new UUID(0L, 100L);
  private static final UUID CHILD_THREAD_ID = new UUID(0L, 200L);

  @Test
  void acceptsValidReceiptParameters() {
    // 测试意图：验证有效字段能够正确构造 ThreadJoinReceipt 且 getter 返回一致内容。
    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            INVOCATION_ID,
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.COMPLETED,
            "solve issue",
            "all tests pass",
            null,
            null);

    assertEquals(INVOCATION_ID, receipt.invocationId());
    assertEquals(CHILD_THREAD_ID, receipt.childThreadId());
    assertEquals("coder", receipt.agent());
    assertEquals(ThreadJoinOutcome.COMPLETED, receipt.outcome());
    assertEquals("solve issue", receipt.prompt());
    assertEquals("all tests pass", receipt.report());
    assertNull(receipt.partialResult());
    assertNull(receipt.error());

    String xml = receipt.renderCompletionXml();
    assertTrue(xml.contains("<result>\nall tests pass\n</result>"), xml);
  }

  @Test
  void rejectsInvalidConstructorArguments() {
    // 测试意图：验证关键字段非空与合法性约束。
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoinReceipt(
                null, CHILD_THREAD_ID, "coder", ThreadJoinOutcome.COMPLETED, "p", "r", null, null));

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID, null, "coder", ThreadJoinOutcome.COMPLETED, "p", "r", null, null));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID,
                CHILD_THREAD_ID,
                "  ",
                ThreadJoinOutcome.COMPLETED,
                "p",
                "r",
                null,
                null));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID,
                CHILD_THREAD_ID,
                "a".repeat(257),
                ThreadJoinOutcome.COMPLETED,
                "p",
                "r",
                null,
                null));

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID, CHILD_THREAD_ID, "coder", null, "p", "r", null, null));

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID,
                CHILD_THREAD_ID,
                "coder",
                ThreadJoinOutcome.COMPLETED,
                null,
                "r",
                null,
                null));
  }

  @Test
  void acceptsBoundaryAgentLength() {
    // 测试意图：验证 agent 边界长度（1 字符与 256 字符）均可合法构造。
    assertDoesNotThrow(
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID,
                CHILD_THREAD_ID,
                "a",
                ThreadJoinOutcome.COMPLETED,
                "p",
                "r",
                null,
                null));

    assertDoesNotThrow(
        () ->
            new ThreadJoinReceipt(
                INVOCATION_ID,
                CHILD_THREAD_ID,
                "x".repeat(256),
                ThreadJoinOutcome.COMPLETED,
                "p",
                "r",
                null,
                null));
  }
}
