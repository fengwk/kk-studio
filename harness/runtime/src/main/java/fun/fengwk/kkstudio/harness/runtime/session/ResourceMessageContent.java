package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.util.Objects;

/** 供上下文使用的 Resource 引用及可选小型预览，不做任何解引用。 */
public record ResourceMessageContent(ResourceRef resource, String preview)
    implements AgentMessageContent {

  public ResourceMessageContent {
    resource = Objects.requireNonNull(resource, "resource");
    if (preview != null
        && ResourceRef.utf8Length(preview, "preview") > ResourceRef.MAX_PREVIEW_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "preview must not exceed " + ResourceRef.MAX_PREVIEW_UTF8_BYTES + " UTF-8 bytes");
    }
  }
}
