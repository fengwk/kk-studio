package fun.fengwk.kkstudio.share.notification;

import java.util.UUID;

/** A null node denotes broadcast; a UUID denotes exactly one App process. */
public record NotificationAddress(UUID nodeId) {
  public static NotificationAddress broadcast() {
    return new NotificationAddress(null);
  }

  public static NotificationAddress node(UUID nodeId) {
    if (nodeId == null) {
      throw new IllegalArgumentException("nodeId is required");
    }
    return new NotificationAddress(nodeId);
  }
}
