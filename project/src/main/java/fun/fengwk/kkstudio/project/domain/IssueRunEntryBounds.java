package fun.fengwk.kkstudio.project.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Run 的历史区间 {@code (startEntryId, endEntryId]} 与报告坐标，沿 Entry 父链解释，不按时间排序。
 *
 * <p>{@code endEntryId} 为 null 表示 Run 尚未收尾；{@code finalAnswerEntryId} 可空（异常没有报告时展示原因），
 * 只能在收尾后给出，且必须是区间内真实可见回答。区间与回答是否真的落在同一 Session/Thread 父链上， 只能由 Runtime 在读取 Thread 历史时验证。
 */
public record IssueRunEntryBounds(UUID startEntryId, UUID endEntryId, UUID finalAnswerEntryId) {

  public IssueRunEntryBounds {
    Objects.requireNonNull(startEntryId, "startEntryId");
    if (finalAnswerEntryId != null && endEntryId == null) {
      throw new IllegalArgumentException("final answer requires a closed entry interval");
    }
    if (startEntryId.equals(finalAnswerEntryId)) {
      throw new IllegalArgumentException("final answer must not be the interval start");
    }
  }
}
