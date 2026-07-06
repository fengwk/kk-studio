package fun.fengwk.kkstudio.agent.tool.execution;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ToolExecutionContentGuard 维护工具回调内容的槽位类型约束与媒体字段校验。
 *
 * @author fengwk
 */
final class ToolExecutionContentGuard {

  private final Consumer<String> warnIgnoredContent;
  private final Map<Integer, ToolContentType> contentTypes = new HashMap<>();

  ToolExecutionContentGuard(Consumer<String> warnIgnoredContent) {
    if (warnIgnoredContent == null) {
      throw new IllegalArgumentException("warnIgnoredContent must not be null");
    }
    this.warnIgnoredContent = warnIgnoredContent;
  }

  List<IndexedToolContentDelta> sanitizePartial(List<IndexedToolContentDelta> partial) {
    List<IndexedToolContentDelta> sanitized = new ArrayList<>();
    for (IndexedToolContentDelta item : partial) {
      if (isUsableDelta(item)) {
        sanitized.add(item);
      }
    }
    return List.copyOf(sanitized);
  }

  List<ToolContent> validateCompleteResult(List<ToolContent> result) {
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
        warnIgnoredContent.accept("missing_text_content_delta");
        return false;
      }
    } else {
      validateMedia(type, contentDelta.getData(), contentDelta.getMime());
    }
    contentTypes.putIfAbsent(index, type);
    return true;
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
}
