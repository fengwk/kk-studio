package fun.fengwk.kkstudio.workspace.dto;

import java.util.List;
import java.util.UUID;

public record WorkspaceFolderDTO(
    UUID id,
    String name,
    List<WorkspaceFolderDTO> children,
    List<WorkspaceResourceDTO> resources) {}
