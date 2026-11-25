package fun.fengwk.kkstudio.workspace.dto;

import java.util.UUID;

public record ResourceRefDTO(WorkspaceResourceKind kind, UUID id, String treeVersion) {}
