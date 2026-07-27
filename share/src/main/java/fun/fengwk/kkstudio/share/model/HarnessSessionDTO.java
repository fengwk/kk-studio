package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Session query projection.
 *
 * <p>{@code updateTime} is an observable derived value (max {@code created_at} over the Session's
 * Entry Tree, falling back to the Session's own {@code created_at}); it is not a stored column.
 */
@Data
public class HarnessSessionDTO {
  private String sessionId;
  private String title;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
