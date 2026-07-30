package fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * PostgreSQL-only row mapping for {@code harness_interaction}; never crosses the Runtime boundary.
 */
@Data
public class InteractionDO {
  private Long id;
  private String ownerKind;
  private Long ownerId;
  private String handlerType;
  private String requestJson;
  private String status;
  private String responseJson;
  private OffsetDateTime expiresAt;
  private Long version;
  private OffsetDateTime createdAt;
  private OffsetDateTime resolvedAt;
}
