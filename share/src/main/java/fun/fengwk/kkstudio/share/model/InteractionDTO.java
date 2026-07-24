package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.Instant;

/**
 * Generic durable Interaction projection. Ids and version are decimal strings at the API boundary.
 */
@Data
public class InteractionDTO {
  private String id;
  private String ownerKind;
  private String ownerId;
  private String handlerType;
  private String projectionJson;
  private String status;
  private String responseJson;
  private Instant expiresAt;
  private String version;
  private Instant createdAt;
  private Instant resolvedAt;
}
