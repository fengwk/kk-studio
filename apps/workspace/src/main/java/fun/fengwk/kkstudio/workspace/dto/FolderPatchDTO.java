package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record FolderPatchDTO(
    @Nullable String name,
    @Nullable UUID parentId,
    @Nullable UUID beforeId,
    @Nullable String expectedTreeVersion) {}
