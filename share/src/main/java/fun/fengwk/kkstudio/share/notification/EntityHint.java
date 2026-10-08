package fun.fengwk.kkstudio.share.notification;

import java.util.UUID;

/** A null entity denotes authoritative reconciliation of all subscribed entities. */
public record EntityHint(UUID entityId) {}
