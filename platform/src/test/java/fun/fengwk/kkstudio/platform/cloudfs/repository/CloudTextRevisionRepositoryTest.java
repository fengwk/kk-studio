package fun.fengwk.kkstudio.platform.cloudfs.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    // Delete by nodeId
    int deleted = revisionRepository.deleteByNodeId(nodeId);
    assertEquals(2, deleted);
    assertFalse(revisionRepository.findCurrentByNodeId(nodeId).isPresent());
  }
}
