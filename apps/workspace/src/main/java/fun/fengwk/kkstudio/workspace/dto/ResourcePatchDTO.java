package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record ResourcePatchDTO(
    @Nullable String title,
    @Nullable UUID folderId,
    @Nullable UUID beforeId,
    @Nullable String expectedTreeVersion) {}
