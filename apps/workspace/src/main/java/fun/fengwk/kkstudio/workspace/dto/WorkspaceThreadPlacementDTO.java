package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record WorkspaceThreadPlacementDTO(
    UUID sessionId,
    String sessionTitle,
    UUID folderId,
    String title,
    WorkspaceThreadState state,
    @Nullable WorkspaceThreadStateReason reason,
    String version) {}
