package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record FolderCreateDTO(@Nullable UUID parentId, String name, String expectedTreeVersion) {}
