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
    if (nodeId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(toDomain(mapper.getCurrentByNodeId(nodeId)));
  }

  @Override
  public Optional<CloudTextRevision> findByNodeIdAndRevision(UUID nodeId, long revision) {
    if (nodeId == null || revision <= 0) {
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
    if (nodeId == null || expectedRevision <= 0) {
      return 0;
    }
    return mapper.unsetCurrent(nodeId, expectedRevision);
  }

  @Override
  public int deleteByNodeId(UUID nodeId) {
    if (nodeId == null) {
      return 0;
    }
    return mapper.deleteByNodeId(nodeId);
  }

  private CloudTextRevision toDomain(CloudTextRevisionDO revisionDO) {
    if (revisionDO == null) {
      return null;
    }
    return CloudTextRevision.builder()
        .nodeId(revisionDO.getNodeId())
        .revision(revisionDO.getRevision() != null ? revisionDO.getRevision() : 0L)
        .content(revisionDO.getContent())
        .sizeBytes(revisionDO.getSizeBytes() != null ? revisionDO.getSizeBytes() : 0L)
        .sha256(revisionDO.getSha256())
        .current(Boolean.TRUE.equals(revisionDO.getIsCurrent()))
        .createdAt(revisionDO.getCreateTime())
        .build();
  }

  private CloudTextRevisionDO toDO(CloudTextRevision revision) {
    if (revision == null) {
      return null;
    }
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
