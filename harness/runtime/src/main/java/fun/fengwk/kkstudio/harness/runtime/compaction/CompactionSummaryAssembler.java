package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 从一次成功的压缩 Model 响应组装 durable {@link CompactionPayload}。
 *
 * <p>HISTORY 成功写出 partial payload（仅摘要文本，不追加文件清单）；FULL 直接使用响应文本并追加文件清单；TURN_PREFIX 只合并 精确引用的紧邻
 * HISTORY 结果文本（{@code historyCompactionEntryId}，绝不扫描更早的 stale partial），随后分 phase 统一追加 重算的文件清单
 * section。phase / trigger / cut 全部从 enclosing {@link CompactionStart} 派生，payload 本身只存 summaryText。
 */
public final class CompactionSummaryAssembler {

  /** 切分 turn 最终 summary 中 history 与 prefix 的分隔格式。 */
  static final String TURN_PREFIX_SEPARATOR = "\n\n---\n\n**Turn Context (split turn):**\n\n";

  /** direct TURN_PREFIX 没有可摘要历史时使用的固定 history 段。 */
  static final String NO_PRIOR_HISTORY = "No prior history.";

  private CompactionSummaryAssembler() {}

  /** 组装最终 payload；{@code responseText} 必须是已通过 ModelResponseValidator 的非空摘要文本。 */
  public static CompactionPayload resultPayload(
      EntryPath path, CompactionStart start, String responseText) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(responseText, "responseText");
    String canonicalResponse = CompactionFileSections.stripReservedSections(responseText);
    if (canonicalResponse.isBlank()) {
      throw new IllegalArgumentException(
          "compaction response must contain text outside reserved file sections");
    }
    if (start.phase() == CompactionPhase.HISTORY) {
      // HISTORY partial：只存摘要文本；文件 section 只在最终 complete 结果由 Runtime 累加重算。
      return new CompactionPayload(canonicalResponse);
    }
    String summary;
    if (start.phase() == CompactionPhase.TURN_PREFIX) {
      String history =
          start.historyCompactionEntryId() == null
              ? NO_PRIOR_HISTORY
              : referencedHistorySummary(path, start.historyCompactionEntryId());
      summary = history + TURN_PREFIX_SEPARATOR + canonicalResponse;
    } else {
      summary = canonicalResponse;
    }
    summary = CompactionFileSections.append(path, start.cutEntryId(), summary);
    return new CompactionPayload(summary);
  }

  /** TURN_PREFIX 最终 summary 中 history 部分：精确引用紧邻 HISTORY 结果 Entry，缺失/类型不符即分支损坏 fail closed。 */
  static String referencedHistorySummary(EntryPath path, UUID historyCompactionEntryId) {
    Objects.requireNonNull(historyCompactionEntryId, "historyCompactionEntryId");
    List<Entry> entries = path.entries();
    for (Entry entry : entries) {
      if (entry.payload() instanceof CompactionPayload payload
          && entry.id().equals(historyCompactionEntryId)) {
        return payload.summaryText();
      }
    }
    throw new IllegalStateException(
        "TURN_PREFIX requires its referenced HISTORY compaction result entry "
            + historyCompactionEntryId
            + " on the current path");
  }
}
