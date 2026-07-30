package fun.fengwk.kkstudio.core.ai.runtime.model.worker;

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
            SafeStreamSnapshot.EMPTY, new SafeStreamSnapshot("hello", "think", 1L));
    assertEquals(new SafeStreamSnapshot("hello", "think", 1L), merged);
  }

  @Test
  void incomingExtendsDurableTextAndThinking() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hel", "rea", 1L);
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hello", "reason", 2L);
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertEquals(incoming, merged);
  }

  @Test
  void incomingOldPrefixKeepsDurable() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hello", "reasoning", 2L);
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hel", "rea", 1L);
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertSame(durable, merged);
  }

  @Test
  void equalDurableAndIncomingReturnsDurableInstance() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hello", "think", 1L);
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hello", "think", 1L);
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertSame(durable, merged);
  }

  @Test
  void onlyTextFieldExtendsKeepsThinkingIdentical() {
    SafeStreamSnapshot durable = new SafeStreamSnapshot("hel", "think", 1L);
    SafeStreamSnapshot incoming = new SafeStreamSnapshot("hello", "think", 2L);
    SafeStreamSnapshot merged = SafeStreamSnapshotMonotonicity.merge(durable, incoming);
    assertEquals(new SafeStreamSnapshot("hello", "think", 2L), merged);
  }

  @Test
  void textForkIsDivergentWithoutLeakingContent() {
    SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException error =
        assertThrows(
            SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException.class,
            () ->
                SafeStreamSnapshotMonotonicity.merge(
                    new SafeStreamSnapshot("left", "", 1L),
                    new SafeStreamSnapshot("right", "", 2L)));
    String message = error.getMessage();
    assertNotNull(message);
    assertEquals(
        "text safe stream snapshot must not shrink or diverge at a newer sequence", message);
  }

  @Test
  void thinkingForkIsDivergentWithoutLeakingContent() {
    SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException error =
        assertThrows(
            SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException.class,
            () ->
                SafeStreamSnapshotMonotonicity.merge(
                    new SafeStreamSnapshot("", "think-a", 1L),
                    new SafeStreamSnapshot("", "think-b", 2L)));
    assertEquals(
        "thinking safe stream snapshot must not shrink or diverge at a newer sequence",
        error.getMessage());
  }

  @Test
  void equalSequenceCannotChangeContent() {
    SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException error =
        assertThrows(
            SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException.class,
            () ->
                SafeStreamSnapshotMonotonicity.merge(
                    new SafeStreamSnapshot("left", "", 2L),
                    new SafeStreamSnapshot("left-more", "", 2L)));
    assertEquals(
        "safe stream snapshot content changed without advancing sequence", error.getMessage());
  }

  @Test
  void newerSequenceCannotShrinkContent() {
    assertThrows(
        SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException.class,
        () ->
            SafeStreamSnapshotMonotonicity.merge(
                new SafeStreamSnapshot("complete", "", 2L),
                new SafeStreamSnapshot("comp", "", 3L)));
  }
}
