package fun.fengwk.kkstudio.harness.runtime.compaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 从被摘要分支重算的 Pi 风格 {@code <read-files>} / {@code <modified-files>} 文件清单。
 *
 * <p>从分支 durable 历史起点（ROOT 之后）累计扫描到被摘要范围终点（cut，HISTORY 阶段为 turnPrefixStart）内 ASSISTANT 消息的
 * read/write/edit tool call {@code path} 参数——完整 Entry 历史保持 durable，因此旧文件操作（包括早于之前压缩的） 不会丢失。modified
 * 集合为 write ∪ edit，read-only 为 read − modified；最终按字典序输出 Pi 格式，空清单不产生 section。
 */
public final class CompactionFileSections {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern RESERVED_SECTION =
      Pattern.compile(
          "<read-files>.*?</read-files>|<modified-files>.*?</modified-files>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern RESERVED_TAG =
      Pattern.compile("</?(?:read|modified)-files>", Pattern.CASE_INSENSITIVE);

  private CompactionFileSections() {}

  /** 把文件清单 section 追加到 summary 文本（无清单时原样返回）。 */
  public static String append(EntryPath path, CompactionRequest request, String summary) {
    String canonicalSummary = stripReservedSections(summary);
    if (canonicalSummary.isBlank()) {
      throw new IllegalArgumentException(
          "compaction summary must contain text outside reserved file sections");
    }
    String sections = sections(path, request);
    return sections.isEmpty() ? canonicalSummary : canonicalSummary + "\n\n" + sections;
  }

  /** 剥离 runtime-owned file sections；模型输出残留不完整 reserved tag 时 fail closed，避免把冲突/畸形标签持久化。 */
  public static String stripReservedSections(String text) {
    Objects.requireNonNull(text, "text");
    String stripped = RESERVED_SECTION.matcher(text).replaceAll("");
    if (RESERVED_TAG.matcher(stripped).find()) {
      throw new IllegalArgumentException(
          "compaction summary contains an incomplete reserved file section");
    }
    return stripped.strip();
  }

  static String sections(EntryPath path, CompactionRequest request) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(request, "request");
    List<Entry> entries = path.entries();
    // 从分支起点（索引 0）累计；HISTORY phase 只扫到 turnPrefixStartEntryId，完整阶段（FULL/TURN_PREFIX）扫到
    // cutEntryId。
    long rangeEndId =
        request.phase() == CompactionPhase.HISTORY && request.turnPrefixStartEntryId() != null
            ? request.turnPrefixStartEntryId()
            : request.cutEntryId();
    int rangeEnd = indexOfId(entries, rangeEndId);
    if (rangeEnd < 0) {
      // 切分事实缺失视为分支损坏，fail closed（绝不静默输出空清单）。
      throw new IllegalStateException(
          "compaction file-section range end entry " + rangeEndId + " is not on the current path");
    }
    Set<String> read = new TreeSet<>();
    Set<String> modified = new TreeSet<>();
    for (int i = 0; i < rangeEnd; i++) {
      Entry entry = entries.get(i);
      if (!(entry.payload() instanceof MessagePayload message)
          || message.message().role() != AgentMessageRole.ASSISTANT) {
        continue;
      }
      for (AgentMessageContent content : message.message().contents()) {
        if (!(content instanceof ToolCallMessageContent call)) {
          continue;
        }
        String pathArgument = pathArgument(call);
        if (pathArgument == null) {
          continue;
        }
        switch (call.toolName()) {
          case "read" -> read.add(pathArgument);
          case "write", "edit" -> modified.add(pathArgument);
          default -> {}
        }
      }
    }
    List<String> readOnly = new ArrayList<>(read);
    readOnly.removeAll(modified);
    List<String> sections = new ArrayList<>();
    if (!readOnly.isEmpty()) {
      sections.add("<read-files>\n" + String.join("\n", readOnly) + "\n</read-files>");
    }
    if (!modified.isEmpty()) {
      sections.add("<modified-files>\n" + String.join("\n", modified) + "\n</modified-files>");
    }
    return sections.isEmpty() ? "" : String.join("\n\n", sections);
  }

  private static String pathArgument(ToolCallMessageContent call) {
    try {
      JsonNode node = MAPPER.readTree(call.argumentsJson());
      if (node != null
          && node.isObject()
          && node.hasNonNull("path")
          && node.get("path").isTextual()) {
        String path = node.get("path").textValue();
        return isSafeSectionPath(path) ? path : null;
      }
    } catch (JsonProcessingException error) {
      return null;
    }
    return null;
  }

  private static boolean isSafeSectionPath(String path) {
    return !path.isBlank()
        && path.indexOf('\r') < 0
        && path.indexOf('\n') < 0
        && !RESERVED_TAG.matcher(path).find();
  }

  private static int indexOfId(List<Entry> entries, long entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id() == entryId) {
        return i;
      }
    }
    return -1;
  }
}
