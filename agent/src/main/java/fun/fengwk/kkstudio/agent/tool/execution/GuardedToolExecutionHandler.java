package fun.fengwk.kkstudio.agent.tool.execution;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * GuardedToolExecutionHandler 保护 Agent 不受非法或迟到工具回调影响。
 *
 * @author fengwk
 */
@Slf4j
class GuardedToolExecutionHandler implements ToolExecutionHandler {

  private final ToolCallRequest request;
  private final ToolExecutionListener listener;
  private final ManagedToolExecutionHandle handle;
  private final Runnable onTerminal;
  private final AtomicBoolean terminal = new AtomicBoolean();
  private final Map<Integer, ToolContentType> contentTypes = new HashMap<>();

  GuardedToolExecutionHandler(
      ToolCallRequest request,
      ToolExecutionListener listener,
      ManagedToolExecutionHandle handle,
      Runnable onTerminal) {
    this.request = requireNonNull(request, "request");
    this.listener = requireNonNull(listener, "listener");
    this.handle = requireNonNull(handle, "handle");
    this.onTerminal = requireNonNull(onTerminal, "onTerminal");
  }

  @Override
  public void onPartial(List<IndexedToolContentDelta> partial) {
    if (terminal.get()) {
      warn("partial_after_terminal");
      return;
    }
    if (partial == null || partial.isEmpty()) {
      warn("empty_partial");
      return;
    }
    List<IndexedToolContentDelta> sanitized = new ArrayList<>();
    for (IndexedToolContentDelta indexedToolContentDelta : partial) {
      try {
        if (isUsableDelta(indexedToolContentDelta)) {
          sanitized.add(indexedToolContentDelta);
        }
      } catch (IllegalArgumentException error) {
        fail(error, true);
        return;
      }
    }
    if (sanitized.isEmpty()) {
      warn("empty_sanitized_partial");
      return;
    }
    listener.onPartial(List.copyOf(sanitized));
  }

  @Override
  public void onComplete(List<ToolContent> result) {
    if (!terminal.compareAndSet(false, true)) {
      warn("complete_after_terminal");
      return;
    }
    List<ToolContent> safeResult;
    try {
      safeResult = validateCompleteResult(result);
    } catch (IllegalArgumentException error) {
      terminal.set(false);
      fail(error, true);
      return;
    }
    onTerminal.run();
    listener.onComplete(safeResult);
  }

  @Override
  public void onError(Throwable error) {
    fail(error, false);
  }

  void onTimeout() {
    fail(new ToolTimeoutException(request.getToolCallId(), request.getToolName(), 0L), true);
  }

  void onTimeout(long timeoutSeconds) {
    fail(
        new ToolTimeoutException(request.getToolCallId(), request.getToolName(), timeoutSeconds),
        true);
  }

  void onRuntimeCancel() {
    if (!terminal.compareAndSet(false, true)) {
      return;
    }
    onTerminal.run();
  }

  private void fail(Throwable error, boolean cancelExecution) {
    if (!terminal.compareAndSet(false, true)) {
      warn("error_after_terminal");
      return;
    }
    onTerminal.run();
    listener.onError(error == null ? new IllegalStateException("unknown tool error") : error);
    if (cancelExecution) {
      handle.cancel();
    }
  }

  private boolean isUsableDelta(IndexedToolContentDelta indexedToolContentDelta) {
    if (indexedToolContentDelta == null) {
      throw invalid("null_tool_content_delta_item");
    }
    Integer index = indexedToolContentDelta.getIndex();
    if (index == null) {
      throw invalid("missing_tool_content_delta_index");
    }
    if (index < 0) {
      throw invalid("negative_tool_content_delta_index");
    }
    ToolContentDelta contentDelta = indexedToolContentDelta.getContentDelta();
    if (contentDelta == null) {
      throw invalid("missing_tool_content_delta_payload");
    }
    ToolContentType type = contentDelta.getType();
    if (type == null) {
      throw invalid("missing_tool_content_type");
    }
    ToolContentType existingType = contentTypes.get(index);
    if (existingType != null && existingType != type) {
      throw invalid("tool_content_type_conflict");
    }
    if (type == ToolContentType.text) {
      if (contentDelta.getText() == null) {
        warn("missing_text_content_delta");
        return false;
      }
    } else {
      validateMedia(type, contentDelta.getData(), contentDelta.getMime());
    }
    contentTypes.putIfAbsent(index, type);
    return true;
  }

  private List<ToolContent> validateCompleteResult(List<ToolContent> result) {
    if (result == null) {
      return List.of();
    }
    for (int i = 0; i < result.size(); i++) {
      ToolContent toolContent = result.get(i);
      if (toolContent == null) {
        throw invalid("null_tool_content");
      }
      ToolContentType type = toolContent.getType();
      if (type == null) {
        throw invalid("missing_tool_content_type");
      }
      ToolContentType existingType = contentTypes.get(i);
      if (existingType != null && existingType != type) {
        throw invalid("tool_content_type_conflict");
      }
      if (type == ToolContentType.text) {
        if (toolContent.getText() == null) {
          throw invalid("missing_text_content");
        }
      } else {
        validateMedia(type, toolContent.getData(), toolContent.getMime());
      }
    }
    return List.copyOf(result);
  }

  private void validateMedia(ToolContentType type, String data, String mime) {
    if (data == null || data.isBlank()) {
      throw invalid("missing_media_data");
    }
    if (mime == null || mime.isBlank()) {
      throw invalid("missing_media_mime");
    }
    if (type == ToolContentType.image && !mime.startsWith("image/")) {
      throw invalid("invalid_image_mime");
    }
    if (type == ToolContentType.audio && !mime.startsWith("audio/")) {
      throw invalid("invalid_audio_mime");
    }
    if (type == ToolContentType.video && !mime.startsWith("video/")) {
      throw invalid("invalid_video_mime");
    }
  }

  private IllegalArgumentException invalid(String reason) {
    return new IllegalArgumentException("invalid tool callback: " + reason);
  }

  private void warn(String reason) {
    log.warn(
        "[tool-execution] ignore tool callback: toolCallId={} toolName={} reason={}",
        request.getToolCallId(),
        request.getToolName(),
        reason);
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
