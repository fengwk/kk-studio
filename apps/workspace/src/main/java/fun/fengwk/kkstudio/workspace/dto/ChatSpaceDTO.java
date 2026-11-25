package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ChatSpaceDTO(
    UUID id,
    String title,
    @Nullable Instant archivedAt,
    String version,
    @Nullable String defaultAgentName,
    @Nullable UUID defaultEnvironmentId,
    boolean defaultYoloEnabled) {}
