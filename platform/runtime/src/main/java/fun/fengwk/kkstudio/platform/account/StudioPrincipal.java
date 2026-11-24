package fun.fengwk.kkstudio.platform.account;

import java.util.UUID;

public record StudioPrincipal(UUID userId, String username, StudioRole role) {}
