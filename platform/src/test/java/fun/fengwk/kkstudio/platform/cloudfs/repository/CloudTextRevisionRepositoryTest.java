package fun.fengwk.kkstudio.platform.cloudfs.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.Optional;
import java.util.UUID;

/** {@link CloudTextRevisionRepository} 持久层集成测试。 */
class CloudTextRevisionRepositoryTest extends PostgresSpringTestSupport {

  @Autowired private CloudNodeRepository nodeRepository;
  @Autowired private CloudTextRevisionRepository revisionRepository;

  /** 验证文本版本 CRUD、CAS 取消当前标记、历史版本读取及按节点级联清理。 */
  @Test
  void testRevisionsCrudAndCasUnsetCurrent() {
    UUID nodeId = UUID.randomUUID();
    CloudNode node =
        CloudNode.builder()
            .id(nodeId)
            .parentId(null)
            .name("test.txt")
            .kind(CloudNodeKind.TEXT)
            .version(0L)
            .build();
    nodeRepository.insert(node);

    CloudTextRevision rev1 =
        CloudTextRevision.builder()
            .nodeId(nodeId)
            .revision(1L)
            .content("content v1")
            .sizeBytes(10)
            .sha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            .current(true)
            .build();
    revisionRepository.insert(rev1);

    Optional<CloudTextRevision> curOpt = revisionRepository.findCurrentByNodeId(nodeId);
    assertTrue(curOpt.isPresent());
    assertEquals(1L, curOpt.get().getRevision());
    assertTrue(curOpt.get().isCurrent());

    // CAS unsetCurrent: wrong revision fails
    int unsetWrong = revisionRepository.unsetCurrent(nodeId, 99L);
    assertEquals(0, unsetWrong);

    // CAS unsetCurrent: non-positive revision returns 0
    assertEquals(0, revisionRepository.unsetCurrent(nodeId, 0L));
    assertEquals(0, revisionRepository.unsetCurrent(nodeId, -1L));

    // CAS unsetCurrent: correct revision succeeds
    int unsetCorrect = revisionRepository.unsetCurrent(nodeId, 1L);
    assertEquals(1, unsetCorrect);

    // No current revision now
    assertFalse(revisionRepository.findCurrentByNodeId(nodeId).isPresent());

    // Insert rev2 as current
    CloudTextRevision rev2 =
        CloudTextRevision.builder()
            .nodeId(nodeId)
            .revision(2L)
            .content("content v2")
            .sizeBytes(10)
            .sha256("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
            .current(true)
            .build();
    revisionRepository.insert(rev2);

    Optional<CloudTextRevision> cur2Opt = revisionRepository.findCurrentByNodeId(nodeId);
    assertTrue(cur2Opt.isPresent());
    assertEquals(2L, cur2Opt.get().getRevision());

    // Historical lookup
    Optional<CloudTextRevision> hist1Opt = revisionRepository.findByNodeIdAndRevision(nodeId, 1L);
    assertTrue(hist1Opt.isPresent());
    assertFalse(hist1Opt.get().isCurrent());

    // Non-positive revision returns empty
    assertTrue(revisionRepository.findByNodeIdAndRevision(nodeId, 0L).isEmpty());
    assertTrue(revisionRepository.findByNodeIdAndRevision(nodeId, -1L).isEmpty());

    // Delete by nodeId
    int deleted = revisionRepository.deleteByNodeId(nodeId);
    assertEquals(2, deleted);
    assertFalse(revisionRepository.findCurrentByNodeId(nodeId).isPresent());
  }

  /** 验证数据库层对 size_bytes 必须严格等于 octet_length(content) 的约束检查。 */
  @Test
  void testSchemaCheckSizeBytesMatchesContentOctetLength() {
    UUID nodeId = UUID.randomUUID();
    CloudNode node =
        CloudNode.builder()
            .id(nodeId)
            .parentId(null)
            .name("size_mismatch.txt")
            .kind(CloudNodeKind.TEXT)
            .version(0L)
            .build();
    nodeRepository.insert(node);

    // content has length 5, but sizeBytes specified as 99
    CloudTextRevision mismatch =
        CloudTextRevision.builder()
            .nodeId(nodeId)
            .revision(1L)
            .content("hello")
            .sizeBytes(99)
            .sha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            .current(true)
            .build();

    assertThrows(DataIntegrityViolationException.class, () -> revisionRepository.insert(mismatch));
  }

  /** 验证仓储接口对入参 null 的严格校验拦截。 */
  @Test
  void testRepositoryRejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> revisionRepository.findCurrentByNodeId(null));
    assertThrows(
        NullPointerException.class, () -> revisionRepository.findByNodeIdAndRevision(null, 1L));
    assertThrows(NullPointerException.class, () -> revisionRepository.insert(null));
    assertThrows(NullPointerException.class, () -> revisionRepository.unsetCurrent(null, 1L));
    assertThrows(NullPointerException.class, () -> revisionRepository.deleteByNodeId(null));
  }
}
