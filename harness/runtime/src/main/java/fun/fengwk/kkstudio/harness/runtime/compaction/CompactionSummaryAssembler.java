package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;

import java.util.List;
import java.util.Objects;

/**
 * 从一次成功的压缩 Model 响应组装 durable {@link CompactionPayload}。
 *
 * <p>HISTORY 成功写出 {@code complete=false} 的中间 payload；FULL 直接使用响应文本；TURN_PREFIX 只合并 <strong>紧邻前一个已关闭
 * turn</strong> 的匹配 incomplete HISTORY payload（其余情况固定为 {@code "No prior history."}——绝不扫描任意更早的陈旧
 * partial），最后统一追加重算的文件清单 section，写出 {@code complete=true}。
 */
public final class CompactionSummaryAssembler {

  /** 切分 turn 最终 summary 中 history 与 prefix 的分隔格式（对齐上游 Pi）。 */
  static final String TURN_PREFIX_SEPARATOR = "\n\n---\n\n**Turn Context (split turn):**\n\n";

  /** 切分但无先前历史时的稳定 history 文本。 */
  static final String NO_PRIOR_HISTORY = "No prior history.";

  private CompactionSummaryAssembler() {}

  /** 组装最终 payload；{@code responseText} 必须是已通过 ModelResponseValidator 的非空摘要文本。 */
  public static CompactionPayload resultPayload(
      CompactionRequest request, String responseText, EntryPath path) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(responseText, "responseText");
    Objects.requireNonNull(path, "path");
    String canonicalResponse = CompactionFileSections.stripReservedSections(responseText);
    if (canonicalResponse.isBlank()) {
      throw new IllegalArgumentException(
          "compaction response must contain text outside reserved file sections");
    }
    if (request.phase() == CompactionPhase.HISTORY) {
      return new CompactionPayload(
          request.phase(),
          request.trigger(),
          request.tokensBefore(),
          false,
          canonicalResponse,
          request.firstKeptEntryId(),
          request.cutEntryId(),
          request.turnPrefixStartEntryId());
    }
    String summary;
    if (request.phase() == CompactionPhase.TURN_PREFIX) {
      summary = latestIncompleteSummary(path, request) + TURN_PREFIX_SEPARATOR + canonicalResponse;
    } else {
      summary = canonicalResponse;
    }
    summary = CompactionFileSections.append(path, request, summary);
    return new CompactionPayload(
        request.phase(),
        request.trigger(),
        request.tokensBefore(),
        true,
        summary,
        request.firstKeptEntryId(),
        request.cutEntryId(),
        request.turnPrefixStartEntryId());
  }

  /**
   * 机械 TURN_PREFIX 延续的 history 文本：只取紧邻前一个已关闭 turn 的匹配 incomplete HISTORY payload（path head 是 当前
   * TURN_PREFIX 的 TURN_START，其紧邻前驱是上一个 turn 的 TURN_END，再前一条是该 turn 的 result Entry）。 partial 存在时其
   * 冻结事实（trigger / tokensBefore / firstKept / cut / prefix）必须与 TURN_PREFIX 请求逐字段一致，不一致视为分支损坏 fail
   * closed；前一个 turn 正常 / 失败 / complete，或存在任意更早的陈旧 partial 时，一律返回 {@value #NO_PRIOR_HISTORY}。
   */
  static String latestIncompleteSummary(EntryPath path, CompactionRequest request) {
    Objects.requireNonNull(request, "request");
    List<Entry> entries = path.entries();
    if (entries.size() < 3) {
      return NO_PRIOR_HISTORY;
    }
    Entry predecessor = entries.get(entries.size() - 2);
    if (!(predecessor.payload() instanceof TurnEndPayload)) {
      return NO_PRIOR_HISTORY;
    }
    Entry previousResult = entries.get(entries.size() - 3);
    if (previousResult.payload() instanceof CompactionPayload payload
        && payload.phase() == CompactionPhase.HISTORY
        && !payload.complete()) {
      if (payload.trigger() != request.trigger()
          || payload.tokensBefore() != request.tokensBefore()
          || !payload.firstKeptEntryId().equals(request.firstKeptEntryId())
          || !payload.cutEntryId().equals(request.cutEntryId())
          || !Objects.equals(payload.turnPrefixStartEntryId(), request.turnPrefixStartEntryId())) {
        throw new IllegalStateException(
            "preceding incomplete HISTORY payload must match the frozen TURN_PREFIX request: "
                + "trigger/tokensBefore/firstKeptEntryId/cutEntryId/turnPrefixStartEntryId");
      }
      return payload.summaryText();
    }
    return NO_PRIOR_HISTORY;
  }
}
