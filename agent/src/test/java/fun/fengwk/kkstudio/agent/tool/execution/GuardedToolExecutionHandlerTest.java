package fun.fengwk.kkstudio.agent.tool.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * GuardedToolExecutionHandler 的边界行为测试。
 *
 * @author fengwk
 */
public class GuardedToolExecutionHandlerTest {

  /** 校验 null、empty 与缺少 text 的 partial 都只会被忽略。 */
  @Test
  public void testIgnoresNonUsablePartial() {
    Fixture fixture = new Fixture();

    fixture.handler.onPartial(null);
    fixture.handler.onPartial(List.of());
    fixture.handler.onPartial(List.of(textDelta(0, null)));

    assertEquals(0, fixture.listener.partials.size());
    assertEquals(0, fixture.listener.errors.size());
  }

  /** 校验非法媒体 partial 会进入错误终态并取消执行。 */
  @Test
  public void testInvalidMediaPartialBecomesError() {
    Fixture fixture = new Fixture();

    fixture.handler.onPartial(List.of(mediaDelta(0, ToolContentType.image, null, "image/png")));

    assertEquals(1, fixture.listener.errors.size());
    assertTrue(fixture.handle.isCancelled());
    assertEquals(1, fixture.terminalCounter.get());
  }

  /** 校验 partial 的空 item、空 index、空 payload、空 type 都会进入错误终态。 */
  @Test
  public void testRejectsMalformedPartialVariants() {
    assertPartialError(
        Collections.singletonList((IndexedToolContentDelta) null), "null_tool_content_delta_item");

    IndexedToolContentDelta missingIndex = new IndexedToolContentDelta();
    missingIndex.setContentDelta(new ToolContentDelta());
    assertPartialError(List.of(missingIndex), "missing_tool_content_delta_index");

    IndexedToolContentDelta missingPayload = new IndexedToolContentDelta();
    missingPayload.setIndex(0);
    assertPartialError(List.of(missingPayload), "missing_tool_content_delta_payload");

    ToolContentDelta missingTypeDelta = new ToolContentDelta();
    IndexedToolContentDelta missingType = new IndexedToolContentDelta();
    missingType.setIndex(0);
    missingType.setContentDelta(missingTypeDelta);
    assertPartialError(List.of(missingType), "missing_tool_content_type");
  }

  /** 校验 complete(null) 会按空结果正常完成。 */
  @Test
  public void testCompleteNullUsesEmptyResult() {
    Fixture fixture = new Fixture();

    fixture.handler.onComplete(null);

    assertEquals(1, fixture.listener.completes.size());
    assertEquals(List.of(), fixture.listener.completes.get(0));
    assertEquals(1, fixture.terminalCounter.get());
  }

  /** 校验非法 complete 会进入错误终态。 */
  @Test
  public void testInvalidCompleteBecomesError() {
    Fixture fixture = new Fixture();
    ToolContent invalid = new ToolContent();
    invalid.setType(ToolContentType.text);

    fixture.handler.onComplete(List.of(invalid));

    assertEquals(1, fixture.listener.errors.size());
    assertTrue(fixture.handle.isCancelled());
  }

  /** 校验 complete 的 null item、null type、媒体 mime 非法都会进入错误终态。 */
  @Test
  public void testRejectsMalformedCompleteVariants() {
    assertCompleteError(Collections.singletonList((ToolContent) null), "null_tool_content");

    ToolContent missingType = new ToolContent();
    assertCompleteError(List.of(missingType), "missing_tool_content_type");

    ToolContent missingMime = new ToolContent();
    missingMime.setType(ToolContentType.image);
    missingMime.setData("img");
    assertCompleteError(List.of(missingMime), "missing_media_mime");

    ToolContent invalidImageMime = new ToolContent();
    invalidImageMime.setType(ToolContentType.image);
    invalidImageMime.setData("img");
    invalidImageMime.setMime("text/plain");
    assertCompleteError(List.of(invalidImageMime), "invalid_image_mime");

    ToolContent invalidAudioMime = new ToolContent();
    invalidAudioMime.setType(ToolContentType.audio);
    invalidAudioMime.setData("audio");
    invalidAudioMime.setMime("text/plain");
    assertCompleteError(List.of(invalidAudioMime), "invalid_audio_mime");

    ToolContent invalidVideoMime = new ToolContent();
    invalidVideoMime.setType(ToolContentType.video);
    invalidVideoMime.setData("video");
    invalidVideoMime.setMime("text/plain");
    assertCompleteError(List.of(invalidVideoMime), "invalid_video_mime");
  }

  /** 校验 partial 与 complete 在同一槽位上类型冲突会失败。 */
  @Test
  public void testCompleteTypeConflictBecomesError() {
    Fixture fixture = new Fixture();
    fixture.handler.onPartial(List.of(textDelta(0, "text")));
    ToolContent image = new ToolContent();
    image.setType(ToolContentType.image);
    image.setData("img");
    image.setMime("image/png");

    fixture.handler.onComplete(List.of(image));

    assertEquals(1, fixture.listener.partials.size());
    assertEquals(1, fixture.listener.errors.size());
    assertTrue(fixture.listener.errors.get(0).getMessage().contains("tool_content_type_conflict"));
  }

  /** 校验 null error 会转为稳定 unknown tool error 文本。 */
  @Test
  public void testNullErrorUsesFallbackMessage() {
    Fixture fixture = new Fixture();

    fixture.handler.onError(null);

    assertEquals(1, fixture.listener.errors.size());
    assertEquals("unknown tool error", fixture.listener.errors.get(0).getMessage());
  }

  /** 校验 runtime cancel 只闭合终态，不向 listener 产生额外事件。 */
  @Test
  public void testRuntimeCancelOnlyClosesTerminal() {
    Fixture fixture = new Fixture();

    fixture.handler.onRuntimeCancel();
    fixture.handler.onPartial(List.of(textDelta(0, "late")));
    fixture.handler.onError(new IllegalStateException("late"));

    assertEquals(0, fixture.listener.partials.size());
    assertEquals(0, fixture.listener.completes.size());
    assertEquals(0, fixture.listener.errors.size());
    assertEquals(1, fixture.terminalCounter.get());
  }

  /** 校验 zero-timeout 变体的异常文本和 getter 可用。 */
  @Test
  public void testZeroTimeoutException() {
    Fixture fixture = new Fixture();

    fixture.handler.onTimeout();

    assertEquals(1, fixture.listener.errors.size());
    ToolTimeoutException error = (ToolTimeoutException) fixture.listener.errors.get(0);
    assertEquals("call_1", error.getToolCallId());
    assertEquals("echo", error.getToolName());
    assertEquals(0L, error.getTimeoutSeconds());
    assertEquals("tool timeout after 0 seconds: echo", error.getMessage());
  }

  /** 校验带秒数的 timeout 变体会保留超时秒数字段。 */
  @Test
  public void testTimeoutExceptionKeepsTimeoutSeconds() {
    Fixture fixture = new Fixture();

    fixture.handler.onTimeout(5L);

    assertEquals(1, fixture.listener.errors.size());
    ToolTimeoutException error = (ToolTimeoutException) fixture.listener.errors.get(0);
    assertEquals("call_1", error.getToolCallId());
    assertEquals("echo", error.getToolName());
    assertEquals(5L, error.getTimeoutSeconds());
    assertEquals("tool timeout after 5 seconds: echo", error.getMessage());
  }

  /** 校验构造入参不能为空。 */
  @Test
  public void testConstructorRejectsNullArguments() {
    Fixture fixture = new Fixture();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GuardedToolExecutionHandler(
                null, fixture.listener, fixture.handle, fixture.terminalCounter::incrementAndGet));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GuardedToolExecutionHandler(
                new ToolCallRequest("call_1", "echo", "{}"),
                null,
                fixture.handle,
                fixture.terminalCounter::incrementAndGet));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GuardedToolExecutionHandler(
                new ToolCallRequest("call_1", "echo", "{}"),
                fixture.listener,
                null,
                fixture.terminalCounter::incrementAndGet));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GuardedToolExecutionHandler(
                new ToolCallRequest("call_1", "echo", "{}"),
                fixture.listener,
                fixture.handle,
                null));
  }

  private static void assertPartialError(
      List<IndexedToolContentDelta> partial, String expectedReason) {
    Fixture fixture = new Fixture();
    fixture.handler.onPartial(partial);
    assertEquals(1, fixture.listener.errors.size());
    assertTrue(fixture.listener.errors.get(0).getMessage().contains(expectedReason));
  }

  private static void assertCompleteError(List<ToolContent> result, String expectedReason) {
    Fixture fixture = new Fixture();
    fixture.handler.onComplete(result);
    assertEquals(1, fixture.listener.errors.size());
    assertTrue(fixture.listener.errors.get(0).getMessage().contains(expectedReason));
  }

  private static IndexedToolContentDelta textDelta(int index, String text) {
    ToolContentDelta contentDelta = new ToolContentDelta();
    contentDelta.setType(ToolContentType.text);
    contentDelta.setText(text);
    IndexedToolContentDelta indexed = new IndexedToolContentDelta();
    indexed.setIndex(index);
    indexed.setContentDelta(contentDelta);
    return indexed;
  }

  private static IndexedToolContentDelta mediaDelta(
      int index, ToolContentType type, String data, String mime) {
    ToolContentDelta contentDelta = new ToolContentDelta();
    contentDelta.setType(type);
    contentDelta.setData(data);
    contentDelta.setMime(mime);
    IndexedToolContentDelta indexed = new IndexedToolContentDelta();
    indexed.setIndex(index);
    indexed.setContentDelta(contentDelta);
    return indexed;
  }

  private static class Fixture {
    private final RecordingListener listener = new RecordingListener();
    private final ManagedToolExecutionHandle handle = new ManagedToolExecutionHandle();
    private final AtomicInteger terminalCounter = new AtomicInteger();
    private final GuardedToolExecutionHandler handler =
        new GuardedToolExecutionHandler(
            new ToolCallRequest("call_1", "echo", "{}"),
            listener,
            handle,
            terminalCounter::incrementAndGet);
  }

  private static class RecordingListener implements ToolExecutionListener {
    private final List<List<IndexedToolContentDelta>> partials = new ArrayList<>();
    private final List<List<ToolContent>> completes = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();

    @Override
    public void onPartial(List<IndexedToolContentDelta> partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(List<ToolContent> result) {
      completes.add(result);
    }

    @Override
    public void onError(Throwable error) {
      errors.add(error);
    }
  }
}
