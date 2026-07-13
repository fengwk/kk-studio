package fun.fengwk.kkstudio.harness.runtime.session;

/** 供上下文使用的 Artifact 引用及小型预览，不把大输出载入 Session。 */
public record ArtifactMessageContent(String artifactId, String mediaType, String preview)
    implements AgentMessageContent {
  public ArtifactMessageContent {
    if (artifactId == null || artifactId.isBlank()) {
      throw new IllegalArgumentException("artifactId must not be blank");
    }
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
  }
}
