package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record ChatSpaceCreateDTO(
    String title,
    String rootFolderName,
    @Nullable String defaultAgentName,
    @Nullable UUID defaultEnvironmentId,
    @Nullable Boolean defaultYoloEnabled) {}
