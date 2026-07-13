package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;

/** 指向完整工具输出 Artifact 的内容单元。 */
public record ArtifactToolContent(ArtifactRef artifact) implements ToolContent {

  public ArtifactToolContent {
    artifact = Objects.requireNonNull(artifact, "artifact");
  }
}
