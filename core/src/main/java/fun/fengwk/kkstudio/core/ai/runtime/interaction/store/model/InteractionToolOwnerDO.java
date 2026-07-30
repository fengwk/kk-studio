package fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** Narrow PostgreSQL Tool owner row needed to resolve a durable Tool permission Interaction. */
@Data
public class InteractionToolOwnerDO {
  private Long id;
  private Long threadId;
  private Long executionEpoch;
  private String status;
  private String permissionState;
  private String location;
  private String environmentName;
  private OffsetDateTime createdAt;
}
