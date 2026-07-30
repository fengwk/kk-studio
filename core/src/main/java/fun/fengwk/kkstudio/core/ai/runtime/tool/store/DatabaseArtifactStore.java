package fun.fengwk.kkstudio.core.ai.runtime.tool.store;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.runtime.tool.store.mapper.ToolArtifactMapper;
import fun.fengwk.kkstudio.core.ai.runtime.tool.store.model.ToolArtifactDO;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Database-backed globally addressable immutable tool output store. */
@Repository
public class DatabaseArtifactStore implements ArtifactStore {
  private final ToolArtifactMapper mapper;
  private final HarnessIdGenerator idGenerator;
  private final Clock clock;

  public DatabaseArtifactStore(
      ToolArtifactMapper mapper, HarnessIdGenerator idGenerator, Clock clock) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ArtifactRef save(String mediaType, String encoding, byte[] content) {
    if (mediaType == null || mediaType.isBlank() || encoding == null || encoding.isBlank()) {
      throw new IllegalArgumentException("artifact metadata must be valid");
    }
    byte[] immutable = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
    long id = idGenerator.nextArtifactId();
    ToolArtifactDO target = new ToolArtifactDO();
    target.setId(id);
    target.setMediaType(mediaType);
    target.setEncoding(encoding);
    target.setContent(immutable);
    target.setSizeBytes((long) immutable.length);
    target.setSha256(sha256(immutable));
    target.setCreatedAt(clock.instant().atOffset(ZoneOffset.UTC));
    if (mapper.insert(target) != 1) {
      throw new IllegalStateException("cannot persist tool artifact");
    }
    return new ArtifactRef(Long.toString(id), mediaType, immutable.length);
  }

  @Override
  public Optional<Artifact> find(String artifactId) {
    if (artifactId == null || artifactId.isBlank()) {
      return Optional.empty();
    }
    try {
      long id = Long.parseLong(artifactId);
      if (id <= 0) {
        return Optional.empty();
      }
      return Optional.ofNullable(mapper.find(id)).map(this::toArtifact);
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private Artifact toArtifact(ToolArtifactDO source) {
    return new Artifact(
        source.getId(),
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
