package fun.fengwk.kkstudio.plugin.comfyui;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 构造 H3 Prompt Agent 的固定 SYSTEM 文本与 USER 文本片段。
 *
 * <p>产物只是普通文本片段：冻结 manifest 表格，以及每个引用一段 label。媒体片段由调用方在打开原始流并暂存后按同一顺序插入，因此文本内容与顺序是 durable-safe
 * 且与提交时机无关的。
 */
public final class H3PromptRequestBuilder {

  private static final String SYSTEM_RESOURCE =
      "/fun/fengwk/kkstudio/plugin/comfyui/minimax-h3-ref2va-system.txt";

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

  /** 首段文本：任务摘要与冻结 manifest 表格（表格是 label 与顺序的权威来源）。 */
  public PluginPromptContent.Text manifestTable(
      CanvasFunctionFrozenRun run, H3ReferenceManifest manifest) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(manifest, "manifest");
    String ratio = extractText(run.args(), "ratio", "16:9");
    int duration = extractInt(run.args(), "duration", 5);
    String userPrompt = extractText(run.args(), "prompt");

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
          .append(reference.sizeBytes())
          .append(" | ")
          .append(note)
          .append(" |\n");
    }
    return new PluginPromptContent.Text(body.toString());
  }

  /** 单个引用的 label 段落：紧随其后的是该引用对应的媒体片段。 */
  public PluginPromptContent.Text referenceLabel(H3ReferenceManifest.Item item) {
    Objects.requireNonNull(item, "item");
    return new PluginPromptContent.Text(
        "\nThe next attachment is " + item.label() + " from the frozen manifest.\n");
  }

  private static String tableCell(String value) {
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }

  private static String readSystemPrompt() {
    try (InputStream stream = H3PromptRequestBuilder.class.getResourceAsStream(SYSTEM_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("MiniMax-H3 system prompt resource is missing");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
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

  private static String extractText(JsonObject args, String field) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonText text) {
      return text.value();
    }
    throw new IllegalArgumentException(field + " must be a string");
  }

  private static String extractText(JsonObject args, String field, String defaultValue) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonText text) {
      return text.value();
    }
    if (json == null) {
      return defaultValue;
    }
    throw new IllegalArgumentException(field + " must be a string");
  }

  private static int extractInt(JsonObject args, String field, int defaultValue) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonNumber number) {
      return number.value().intValueExact();
    }
    if (json == null) {
      return defaultValue;
    }
    throw new IllegalArgumentException(field + " must be an integer");
  }
}
