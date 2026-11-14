package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;

import java.util.List;
import java.util.UUID;

/**
 * 收尾区间解释（设计 §4.5）：{@code (startEntryId,endEntryId]} 必须落在该 Thread 的真实历史路径上。
 *
 * <p>外键只保证 Entry 属于同一 Session，因此必须沿父链判定，绝不能按时间排序或只看区间序号。
 */
final class IssueRunInterval {

  private IssueRunInterval() {}

  /**
   * 复验 end 是 start 的后代，且 final answer 落在同一区间内。
   *
   * <p>{@code requireProgress} 为 true 时要求区间非空（正常收尾必须真的产出过历史），失败/取消/不明允许空区间：接受之后还没有产出任何 Entry
   * 时也必须能安全收尾。
   */
  static void requireWithin(
      EntryPath path,
      UUID startEntryId,
      UUID endEntryId,
      UUID finalAnswerEntryId,
      boolean requireProgress) {
    List<UUID> entryIds = path.entries().stream().map(Entry::id).toList();
    int start = entryIds.indexOf(startEntryId);
    if (start < 0) {
      throw DomainException.validation(
          "issue_run", "Run start entry is not on the bounded thread history path");
    }
    int end = entryIds.indexOf(endEntryId);
    if (end < start || (requireProgress && end == start)) {
      throw DomainException.validation(
          "issue_run", "Run end entry must be a descendant of the run start entry");
    }
    if (finalAnswerEntryId != null) {
      int answer = entryIds.indexOf(finalAnswerEntryId);
      if (answer <= start || answer > end) {
        throw DomainException.validation(
            "issue_run", "Run final answer must be inside the run interval (start, end]");
      }
    }
  }
}
