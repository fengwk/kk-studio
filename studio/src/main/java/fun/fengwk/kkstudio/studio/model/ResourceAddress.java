package fun.fengwk.kkstudio.studio.model;

import java.util.Objects;

/** Stable channel/item address under a ResourceOwner. */
public record ResourceAddress(ResourceOwner owner, String channelKey, String itemKey) {

  public ResourceAddress {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(channelKey, "channelKey");
    Objects.requireNonNull(itemKey, "itemKey");
  }
}
