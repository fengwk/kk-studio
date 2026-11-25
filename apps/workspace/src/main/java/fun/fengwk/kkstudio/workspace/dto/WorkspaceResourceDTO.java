package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record WorkspaceResourceDTO(
    WorkspaceResourceKind kind,
    UUID id,
    String title,
    @Nullable List<WorkspaceThreadDTO> threads) {}
