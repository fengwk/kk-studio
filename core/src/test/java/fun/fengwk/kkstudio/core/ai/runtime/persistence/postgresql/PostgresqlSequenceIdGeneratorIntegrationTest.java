package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.persistence.id.SequenceMapper;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end coverage for the PostgreSQL {@code kk_studio_id_seq} sequence wired through {@link
 * PostgresqlSequenceIdGenerator}.
 *
 * <p>Extends {@link PostgresSchemaSupport} so a real {@code postgres:17-alpine} Testcontainers
 * instance is spun up and {@link #newConnection()} gives a direct JDBC handle to it. Docker must be
 * available; the test fails rather than skipping when it is not. The {@link SequenceMapper} used
 * here is a JDK lambda backed by JDBC rather than a MyBatis proxy, so the test exercises the exact
 * SQL the production mapper issues and not a fake.
 */
class PostgresqlSequenceIdGeneratorIntegrationTest extends PostgresSchemaSupport {

  /**
   * Constant copied verbatim from {@link SequenceMapper#nextValue()} so any drift between the
   * annotation and the JDBC call is caught explicitly.
   */
  private static final String EXPECTED_SELECTION_SQL = "select nextval('kk_studio_id_seq')";

  private PostgresqlSequenceIdGenerator generator;
  private SequenceMapper jdbcMapper;

  @BeforeEach
  void setup() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyE2eDatabase(conn);
    }
    jdbcMapper =
        () -> {
          try (Connection conn = newConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(EXPECTED_SELECTION_SQL)) {
            assertTrue(rs.next(), "nextval must return exactly one row");
            return rs.getLong(1);
          } catch (SQLException ex) {
            throw new IllegalStateException("kk_studio_id_seq nextval failed", ex);
          }
        };
    generator = new PostgresqlSequenceIdGenerator(jdbcMapper);
  }

  /** Lock in the production MyBatis SQL: any drift fails this test. */
  @Test
  void sequenceMapperSelectAnnotationMatchesProductionSql() throws NoSuchMethodException {
    Method nextValue = SequenceMapper.class.getMethod("nextValue");
    Select select = nextValue.getAnnotation(Select.class);
    assertNotNull(select, "@Select must be declared on SequenceMapper.nextValue");
    assertEquals(1, select.value().length, "@Select must declare exactly one statement");
    assertEquals(EXPECTED_SELECTION_SQL, select.value()[0]);
  }

  /** Every allocation must be strictly positive. */
  @Test
  void allocationsAreStrictlyPositive() {
    for (int i = 0; i < 20; i++) {
      long id = generator.next();
      assertTrue(id > 0, "kk_studio_id_seq must produce positive ids, got " + id);
    }
  }

  /**
   * Strict monotonic ordering, no duplicates, shared across distinct call sites. The generated
   * durable business and Harness entities all delegate to the same physical sequence; catalog
   * identities are names and therefore do not consume it.
   */
  @Test
  void allocationsAreStrictlyMonotonicAndSharedAcrossCallSites() {
    int totalCalls = 64;
    Set<Long> seen = new HashSet<>(totalCalls);
    Map<String, Long> lastBySite = new LinkedHashMap<>();

    String[] sites = {
      "comfyui_workflow_api", "canvas_document", "canvas_node", "canvas_link", "chat"
    };

    long previous = 0L;
    for (int i = 0; i < totalCalls; i++) {
      String site = sites[i % sites.length];
      // Round-trip the generator every iteration so the lambda-backed monotonicity is
      // observable; the per-site bookkeeping below mirrors the production wiring where each
      // business generator advances the same physical sequence.
      long fromGenerator = generator.next();
      assertTrue(seen.add(fromGenerator), "duplicate id allocated: " + fromGenerator);
      assertTrue(
          fromGenerator > previous,
          "id must be strictly monotonic: prev=" + previous + " next=" + fromGenerator);
      previous = fromGenerator;
      lastBySite.merge(site, fromGenerator, Math::max);
    }

    assertEquals(totalCalls, seen.size(), "every allocation must be unique");
    for (Map.Entry<String, Long> entry : lastBySite.entrySet()) {
      assertTrue(entry.getValue() > 0L, "site " + entry.getKey() + " saw a non-positive id");
    }
  }

  /**
   * The harness runtime sequence is a separate physical sequence: it allocates positive monotonic
   * ids independently of the business sequence, and it is never aligned by any seed (the runtime
   * policy tables that used to own deterministic singleton ids are gone).
   */
  @Test
  void harnessRuntimeSequenceAllocatesPositiveMonotonicIds() throws Exception {
    long previous = 0L;
    for (int i = 0; i < 16; i++) {
      long id;
      try (Connection conn = newConnection();
          Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select nextval('harness_runtime_id_seq')")) {
        assertTrue(rs.next(), "harness_runtime_id_seq nextval must return exactly one row");
        id = rs.getLong(1);
      }
      assertTrue(id > 0, "harness_runtime_id_seq must produce positive ids, got " + id);
      assertTrue(
          id > previous,
          "harness_runtime_id_seq must be monotonic: prev=" + previous + " next=" + id);
      previous = id;
    }

    // The two sequences are independent physical objects.
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select count(*) from information_schema.sequences"
                    + " where sequence_schema = 'public'"
                    + " and sequence_name in ('kk_studio_id_seq', 'harness_runtime_id_seq')");
        ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(2L, rs.getLong(1), "both declared sequences must exist");
    }
  }
}
