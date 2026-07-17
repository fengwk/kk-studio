package fun.fengwk.kkstudio.studio.model;

import java.util.Objects;

/** Stable logical Resource identity. Content lives on ResourceVersion. */
public record Resource(
    long id,
    long workspaceId,
    ResourceOwner owner,
    String channelKey,
    String itemKey,
    ResourceKind kind,
    String displayName,
    Long currentVersionId,
    ResourceAvailability availability,
    long revision) {

  public Resource {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(channelKey, "channelKey");
    Objects.requireNonNull(itemKey, "itemKey");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(availability, "availability");
  }
}
