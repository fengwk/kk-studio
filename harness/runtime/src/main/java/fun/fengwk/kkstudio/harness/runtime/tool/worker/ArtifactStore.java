package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.util.Optional;

/** Durable artifact boundary. String identifiers are retained at transport boundaries only. */
public interface ArtifactStore {
  ArtifactRef save(long workspaceId, String mediaType, String encoding, byte[] content);

  Optional<Artifact> find(long workspaceId, String artifactId);
}
