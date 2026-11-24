package fun.fengwk.kkstudio.platform.account;

import java.time.Instant;
import java.util.UUID;

public record UserView(
    UUID id, String username, StudioRole role, boolean enabled, Instant createdAt) {}
