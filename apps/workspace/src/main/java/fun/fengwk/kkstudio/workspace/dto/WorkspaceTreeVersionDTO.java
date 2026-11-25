package fun.fengwk.kkstudio.workspace.dto;

public record WorkspaceTreeVersionDTO(String treeVersion) {
  public static WorkspaceTreeVersionDTO of(long version) {
    return new WorkspaceTreeVersionDTO(Long.toString(version));
  }
}
