package fun.fengwk.kkstudio.harness.tool;

/** 由 Runtime 管理、可承载大输出或二进制内容的不可变 Artifact 引用。 */
public record ArtifactRef(String artifactId, String mediaType, long sizeBytes) {

  public ArtifactRef {
    if (artifactId == null || artifactId.isBlank()) {
      throw new IllegalArgumentException("artifactId must not be blank");
    }
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
  }
}
