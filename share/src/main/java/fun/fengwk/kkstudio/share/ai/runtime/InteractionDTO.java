package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * Durable Tool permission Interaction projection. Ids and version are decimal strings at the API
 * boundary.
 */
@Data
public class InteractionDTO {
  private String id;
  private String toolInvocationId;
  private String projectionJson;
  private String status;
  private String responseJson;
  private String version;
  private Instant createdAt;
  private Instant resolvedAt;
}
