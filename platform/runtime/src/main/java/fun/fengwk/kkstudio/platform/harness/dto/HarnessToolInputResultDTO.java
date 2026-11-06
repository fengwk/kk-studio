package fun.fengwk.kkstudio.platform.harness.dto;

import java.time.Instant;

/**
 * 人工输入提交被接受（或精确重放）后的权威回执视图。
 *
 * <p>无论结果是「刚接受」还是「结果已物化后的重试」都返回同一份 durable 事实；{@code materialized} 区分本次是否由已物化的历史回执 replay。
 */
public record HarnessToolInputResultDTO(
    String threadId,
    String toolInvocationId,
    String submissionId,
    String actor,
    Instant acceptedAt,
    boolean materialized) {}
