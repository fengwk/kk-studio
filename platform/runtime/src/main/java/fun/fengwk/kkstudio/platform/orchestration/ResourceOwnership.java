package fun.fengwk.kkstudio.platform.orchestration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The product placement of Harness sessions and Canvas documents, including archived spaces. */
public interface ResourceOwnership {
  enum Kind {
    SESSION,
    CANVAS
  }

  record Owner(UUID spaceId, String spaceTitle, UUID ownerUserId) {}

  /** Missing/unregistered IDs are omitted from the result. */
  Map<UUID, Owner> findOwners(Kind kind, Collection<UUID> ids);

  default Optional<Owner> findOwner(Kind kind, UUID id) {
    return Optional.ofNullable(findOwners(kind, List.of(id)).get(id));
  }
}
