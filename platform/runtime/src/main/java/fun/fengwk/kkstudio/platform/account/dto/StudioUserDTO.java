package fun.fengwk.kkstudio.platform.account.dto;

import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.time.Instant;
import java.util.UUID;

public record StudioUserDTO(
    UUID id, String username, StudioRole role, boolean enabled, Instant createdAt) {}
