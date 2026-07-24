package fun.fengwk.kkstudio.core.harness.interaction.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** Narrow PostgreSQL-only Tool owner row needed for Interaction suspension and terminalization. */
@Data
public class InteractionToolOwnerDO {
  private Long id;
  private Long threadId;
  private String status;
  private OffsetDateTime createdAt;
}
