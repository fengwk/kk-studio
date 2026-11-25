package fun.fengwk.kkstudio.workspace.dto;

import java.util.List;

public record WorkspaceTreeDTO(String treeVersion, List<WorkspaceFolderDTO> folders) {}
