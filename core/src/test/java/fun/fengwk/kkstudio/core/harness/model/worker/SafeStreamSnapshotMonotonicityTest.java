package fun.fengwk.kkstudio.core.harness.model.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;

/**
 * 验证 {@link SafeStreamSnapshotMonotonicity#merge} 的 prefix-单调合并语义；fork 异常消息仅暴露字段名以避免 泄漏生成内容到日志或
 * {@code ASSISTANT_ERROR} 投影面。
 */
class SafeStreamSnapshotMonotonicityTest {

  @Test
  void emptyDurableAcceptsIncomingAsIs() {
    SafeStreamSnapshot merged =
        SafeStreamSnapshotMonotonicity.merge(
            SafeStreamSnapshot.EMPTY, new SafeStreamSnapshot("hello", "think"));
    assertEquals(new SafeStreamSnapshot("hello", "think"), merged);
  }

  @Test
  void incomingExtendsDurableTextAndThinking() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hel", "rea");
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hello", "reason");
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertEquals(incoming, merged);
  }

  @Test
  void incomingOldPrefixKeepsDurable() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hello", "reasoning");
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hel", "rea");
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertSame(durable, merged);
  }

  @Test
  void equalDurableAndIncomingReturnsDurableInstance() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hello", "think");
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hello", "think");
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertSame(durable, merged);
  }

  @Test
  void onlyTextFieldExtendsKeepsThinkingIdentical() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hel", "think");
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hello", "think");
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertEquals(new SafeStreamSnapshot("hello", "think"), merged);
  }

  @Test
  void textForkIsDivergentWithoutLeakingContent() {
    SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException error =
        assertThrows(
            SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException.class,
            () ->
                SafeStreamSnapshotMonotonicity.merge(
                    new SafeStreamSnapshot("left", ""), new SafeStreamSnapshot("right", "")));
    String message = error.getMessage();
    assertNotNull(message);
    assertEquals("text safe stream snapshot is divergent", message);
  }

  @Test
  void thinkingForkIsDivergentWithoutLeakingContent() {
    SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException error =
        assertThrows(
            SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException.class,
            () ->
                SafeStreamSnapshotMonotonicity.merge(
                    new SafeStreamSnapshot("", "think-a"), new SafeStreamSnapshot("", "think-b")));
    assertEquals("thinking safe stream snapshot is divergent", error.getMessage());
  }
}
