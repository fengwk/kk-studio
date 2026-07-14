package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.harness.tool.worker.store.ToolArtifactDO;
import fun.fengwk.kkstudio.core.harness.tool.worker.store.ToolArtifactMapper;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** MyBatis artifact store with mandatory Workspace predicate on reads. */
@Repository
public class DatabaseArtifactStore implements ArtifactStore {
  private final ToolArtifactMapper mapper;

  public DatabaseArtifactStore(ToolArtifactMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public ArtifactRef save(long workspaceId, String mediaType, String encoding, byte[] content) {
    if (workspaceId <= 0
        || mediaType == null
        || mediaType.isBlank()
        || encoding == null
        || encoding.isBlank()) {
      throw new IllegalArgumentException("artifact workspace and metadata must be valid");
    }
    byte[] immutable = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
    long id = AgentIdGenerator.nextToolArtifactId();
    ToolArtifactDO target = new ToolArtifactDO();
    target.setId(id);
    target.setWorkspaceId(workspaceId);
    target.setMediaType(mediaType);
    target.setEncoding(encoding);
    target.setContent(immutable);
    target.setSizeBytes((long) immutable.length);
    target.setSha256(sha256(immutable));
    target.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
    if (mapper.insert(target) != 1) {
      throw new IllegalStateException("cannot persist tool artifact");
    }
    return new ArtifactRef(Long.toString(id), mediaType, immutable.length);
  }

  @Override
  public Optional<Artifact> find(long workspaceId, String artifactId) {
    if (workspaceId <= 0 || artifactId == null || artifactId.isBlank()) {
      return Optional.empty();
    }
    try {
      long id = Long.parseLong(artifactId);
      if (id <= 0) {
        return Optional.empty();
      }
      return Optional.ofNullable(mapper.find(workspaceId, id)).map(this::toArtifact);
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private Artifact toArtifact(ToolArtifactDO source) {
    return new Artifact(
        source.getId(),
        source.getWorkspaceId(),
        source.getMediaType(),
        source.getEncoding(),
        source.getContent(),
        source.getSizeBytes(),
        source.getSha256());
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
