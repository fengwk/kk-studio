package fun.fengwk.kkstudio.core.studio.function.h3;

import org.springframework.core.io.ClassPathResource;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.PromptSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 构造 H3 Prompt Agent 的固定 SYSTEM 与 USER 消息。
 *
 * <p>USER 消息只包含 durable-safe 的 TEXT 内容（manifest 表格 + 每个引用一个 label 段落）；媒体内容不进 durable JSON，由提交方在入队
 * preflight 中按 manifest 顺序物化为全局存储 RESOURCE 内容（见 {@link MiniMaxH3CanvasFunctionAdapter}）。
 */
public final class H3PromptRequestBuilder {

  private static final String SYSTEM_RESOURCE =
      "fun/fengwk/kkstudio/core/studio/function/h3/minimax-h3-ref2va-system.txt";

  private final String systemPrompt;

  public H3PromptRequestBuilder() {
    systemPrompt = readSystemPrompt();
  }

  H3PromptRequestBuilder(String systemPrompt) {
    this.systemPrompt = requireText(systemPrompt, "systemPrompt");
  }

  public String systemPrompt() {
    return systemPrompt;
  }

  public AgentMessage userMessage(CanvasFunctionFrozenRun run, H3ReferenceManifest manifest) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(manifest, "manifest");
    String ratio = (String) run.config().parameters().get("ratio");
    int duration = (Integer) run.config().parameters().get("duration");
    String userPrompt = renderUserPrompt(run, manifest);

    StringBuilder body =
        new StringBuilder()
            .append("Task: MiniMax-H3 Ref2VA\n")
            .append("Target duration: ")
            .append(duration)
            .append(" seconds\n")
            .append("Target ratio: ")
            .append(ratio)
            .append("\n\n")
            .append("Original user prompt with frozen reference labels (preserve its wording):\n")
            .append(userPrompt)
            .append("\n\nFrozen manifest (this table is authoritative for labels and order):\n")
            .append("| label | kind | resourceId | name | mediaType | sizeBytes | note |\n")
            .append("|---|---|---:|---|---|---:|---|\n");
    for (H3ReferenceManifest.Item item : manifest.items()) {
      var reference = item.reference();
      String note =
          item.kind() == CanvasResourceKind.VIDEO
              ? "embedded audio, if present, belongs to this same video label"
              : "standalone reference";
      body.append("| ")
          .append(item.label())
          .append(" | ")
          .append(item.kind())
          .append(" | ")
          .append(reference.resourceId())
          .append(" | ")
          .append(tableCell(reference.name()))
          .append(" | ")
          .append(reference.mediaType())
          .append(" | ")
          .append(reference.size())
          .append(" | ")
          .append(note)
          .append(" |\n");
    }

    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent(body.toString()));
    for (H3ReferenceManifest.Item item : manifest.items()) {
      contents.add(
          new TextMessageContent(
              "\nThe next attachment is " + item.label() + " from the frozen manifest.\n"));
    }
    return new AgentMessage(AgentMessageRole.USER, contents);
  }

  private static String renderUserPrompt(
      CanvasFunctionFrozenRun run, H3ReferenceManifest manifest) {
    Map<ReferenceKey, String> labels = new LinkedHashMap<>();
    for (H3ReferenceManifest.Item item : manifest.items()) {
      labels.put(
          new ReferenceKey(item.reference().sourceNodeId(), item.reference().sourceIndex()),
          item.label());
    }
    StringBuilder prompt = new StringBuilder();
    for (PromptSegment segment : run.config().segments()) {
      switch (segment) {
        case TextSegment text -> prompt.append(text.text());
        case ReferenceSegment reference -> {
          String label = labels.get(new ReferenceKey(reference.nodeId(), reference.index()));
          if (label == null) {
            throw new IllegalArgumentException(
                "prompt reference is absent from frozen H3 manifest");
          }
          prompt.append(label);
        }
      }
    }
    return prompt.toString();
  }

  private static String tableCell(String value) {
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }

  private static String readSystemPrompt() {
    try {
      return new ClassPathResource(SYSTEM_RESOURCE)
          .getContentAsString(StandardCharsets.UTF_8)
          .strip();
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read H3 system prompt", error);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private record ReferenceKey(long nodeId, int index) {}
}
