package fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.repository.CloudTextRevisionRepository;
import fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.mapper.CloudTextRevisionMapper;
import fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.model.CloudTextRevisionDO;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 基于 PostgreSQL MyBatis 的 {@link CloudTextRevisionRepository} 实现。 */
@Repository
public class PostgresqlCloudTextRevisionRepository implements CloudTextRevisionRepository {

  private final CloudTextRevisionMapper mapper;

  public PostgresqlCloudTextRevisionRepository(CloudTextRevisionMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public Optional<CloudTextRevision> findCurrentByNodeId(UUID nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    return Optional.ofNullable(toDomain(mapper.getCurrentByNodeId(nodeId)));
  }

  @Override
  public Optional<CloudTextRevision> findByNodeIdAndRevision(UUID nodeId, long revision) {
    Objects.requireNonNull(nodeId, "nodeId");
    if (revision <= 0) {
      return Optional.empty();
    }
    return Optional.ofNullable(toDomain(mapper.getByNodeIdAndRevision(nodeId, revision)));
  }

  @Override
  public void insert(CloudTextRevision revision) {
    Objects.requireNonNull(revision, "revision");
    CloudTextRevisionDO revisionDO = toDO(revision);
    mapper.insert(revisionDO);
  }

  @Override
  public int unsetCurrent(UUID nodeId, long expectedRevision) {
    Objects.requireNonNull(nodeId, "nodeId");
    if (expectedRevision <= 0) {
      return 0;
    }
    return mapper.unsetCurrent(nodeId, expectedRevision);
  }

  @Override
  public int deleteByNodeId(UUID nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    return mapper.deleteByNodeId(nodeId);
  }

  private CloudTextRevision toDomain(CloudTextRevisionDO revisionDO) {
    if (revisionDO == null) {
      return null;
    }
    return CloudTextRevision.builder()
        .nodeId(revisionDO.getNodeId())
        .revision(revisionDO.getRevision())
        .content(revisionDO.getContent())
        .sizeBytes(revisionDO.getSizeBytes())
        .sha256(revisionDO.getSha256())
        .current(Boolean.TRUE.equals(revisionDO.getIsCurrent()))
        .createdAt(revisionDO.getCreateTime())
        .build();
  }

  private CloudTextRevisionDO toDO(CloudTextRevision revision) {
    Objects.requireNonNull(revision, "revision");
    return CloudTextRevisionDO.builder()
        .nodeId(revision.getNodeId())
        .revision(revision.getRevision())
        .content(revision.getContent())
        .sizeBytes(revision.getSizeBytes())
        .sha256(revision.getSha256())
        .isCurrent(revision.isCurrent())
        .createTime(revision.getCreatedAt())
        .build();
  }
}
