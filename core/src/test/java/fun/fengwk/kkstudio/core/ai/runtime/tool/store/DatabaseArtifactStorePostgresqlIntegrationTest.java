package fun.fengwk.kkstudio.core.ai.runtime.tool.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** PostgreSQL 17 contracts for immutable globally addressable Tool artifacts. */
class DatabaseArtifactStorePostgresqlIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private DatabaseArtifactStore store;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void savesAndReadsImmutableArtifactWithCanonicalMetadata() {
    byte[] source = "artifact-body".getBytes(StandardCharsets.UTF_8);
    byte[] expected = source.clone();

    ArtifactRef ref = store.save("text/plain", "identity", source);
    source[0] = 'X';

    Artifact stored = store.find(ref.artifactId()).orElseThrow();
    assertEquals(ref.artifactId(), Long.toString(stored.id()));
    assertEquals("text/plain", stored.mediaType());
    assertEquals("identity", stored.encoding());
    assertEquals(expected.length, stored.sizeBytes());
    assertEquals(sha256(expected), stored.sha256());
    assertArrayEquals(expected, stored.content());

    byte[] leaked = stored.content();
    leaked[0] = 'Y';
    assertArrayEquals(expected, store.find(ref.artifactId()).orElseThrow().content());

    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_artifact where id = ? and created_at is not null",
            Integer.class,
            stored.id()));
  }

  @Test
  void returnsEmptyForMissingOrInvalidIdentifiers() {
    assertTrue(store.find("9999999999").isEmpty());
    assertTrue(store.find(null).isEmpty());
    assertTrue(store.find("").isEmpty());
    assertTrue(store.find("not-a-number").isEmpty());
    assertTrue(store.find("0").isEmpty());
    assertTrue(store.find("-1").isEmpty());
  }

  @Test
  void rejectsInvalidArtifactInputsWithoutWritingRows() {
    assertThrows(IllegalArgumentException.class, () -> store.save(" ", "identity", new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class, () -> store.save("text/plain", " ", new byte[] {1}));
    assertThrows(NullPointerException.class, () -> store.save("text/plain", "identity", null));
    assertFalse(
        jdbcTemplate.queryForObject(
            "select exists(select 1 from harness_artifact)", Boolean.class));
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }
}
