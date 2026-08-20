package fun.fengwk.kkstudio.harness.runtime.compaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 从被摘要分支重算的 Pi 风格 {@code <read-files>} / {@code <modified-files>} 文件清单。
 *
 * <p>从分支 durable 历史起点（ROOT 之后索引 0）累计扫描到被摘要范围终点（最终 complete 结果一律为 {@code cutEntryId}—— FULL /
 * TURN_PREFIX 只在最终 complete 结果由 Runtime 从 ROOT..cut 累计重算，HISTORY 不产出文件清单）内 ASSISTANT 消息的
 * read/write/edit tool call {@code path} 参数。modified 集合为 write ∪ edit，read-only 为 read − modified；
 * 最终按字典序输出 Pi 格式，空清单不产生 section。模型输出残留不完整 reserved tag 时 fail closed。
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
  public static String append(EntryPath path, UUID cutEntryId, String summary) {
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    String canonicalSummary = stripReservedSections(summary);
    if (canonicalSummary.isBlank()) {
      throw new IllegalArgumentException(
          "compaction summary must contain text outside reserved file sections");
    }
    String sections = sections(path, cutEntryId);
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

  static String sections(EntryPath path, UUID cutEntryId) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    List<Entry> entries = path.entries();
    int rangeEnd = indexOfId(entries, cutEntryId);
    if (rangeEnd < 0) {
      // cut 缺失视为分支损坏，fail closed（绝不静默输出空清单）。
      throw new IllegalStateException(
          "compaction file-section range end entry " + cutEntryId + " is not on the current path");
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

  private static int indexOfId(List<Entry> entries, UUID entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }
}
