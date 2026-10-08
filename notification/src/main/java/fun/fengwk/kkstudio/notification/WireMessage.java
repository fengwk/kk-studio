package fun.fengwk.kkstudio.notification;

import java.util.UUID;

/** Internal owned encoding; never exposed to domain subscribers. */
record WireMessage(UUID publisher, UUID target, String topic, UUID messageId, byte[] bytes) {}
