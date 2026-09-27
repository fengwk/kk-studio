package fun.fengwk.kkstudio.share.ai.interaction;

import lombok.Data;

import java.time.Instant;

/**
 * 人工输入提交被接受（或精确重放）后的权威回执视图。
 *
 * <p>无论结果是「刚接受」还是「结果已物化后的重试」都返回同一份 durable 事实；{@code materialized} 区分本次是否由已物化的历史回执 replay。
 */
@Data
public class HarnessToolInputResultDTO {

  private String threadId;

  private String interactionId;

  private String submissionId;

  private String actor;

  private Instant acceptedAt;

  private Boolean materialized;
}
