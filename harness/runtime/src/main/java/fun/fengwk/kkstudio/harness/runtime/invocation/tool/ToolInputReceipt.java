package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次已接受人工输入提交的 durable 回执：提交身份、操作者与服务端接受时间。
 *
 * <p>回执只解决“结果尚未进入 Entry 时的重试与崩溃恢复”，因此不保存问卷或答案：答案本身是 ToolResult，问卷原文在 Assistant ToolCall。它只允许出现在
 * SUCCEEDED 且 result 非空的调用上；attempt 为 0（人工作答不是执行）且不携带 approval（问卷不能授权工具）。
 */
public record ToolInputReceipt(UUID submissionId, String actor, Instant acceptedAt) {

  /** actor 的字符上限，与 approval actor 保持一致。 */
  public static final int ACTOR_MAX_LENGTH = 128;

  public ToolInputReceipt {
    submissionId = Objects.requireNonNull(submissionId, "submissionId");
    actor = requireCanonicalActor(actor);
    acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
  }

  /** 该回执是否由给定的提交身份与操作者重试而来（答案比较由调用方按规范化结构完成）。 */
  public boolean replays(UUID candidateSubmissionId, String candidateActor) {
    return submissionId.equals(candidateSubmissionId) && actor.equals(candidateActor);
  }

  private static String requireCanonicalActor(String actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.isBlank()) {
      throw new IllegalArgumentException("actor must not be blank");
    }
    if (!actor.equals(actor.strip())) {
      throw new IllegalArgumentException("actor must not contain surrounding whitespace");
    }
    if (actor.length() > ACTOR_MAX_LENGTH) {
      throw new IllegalArgumentException("actor must be <= " + ACTOR_MAX_LENGTH + " characters");
    }
    return actor;
  }
}
