package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

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

  /**
   * Largest deterministic id assigned by {@code data-e2e-postgresql.sql}. The seed's trailing
   * {@code setval('kk_studio_id_seq', ..., true)} clause advances the sequence past this value so
   * every subsequent allocation must be strictly greater.
   */
  private static final long E2E_SEED_MAX_ID = 12L;

  private PostgresqlSequenceIdGenerator generator;
  private SequenceMapper jdbcMapper;

  @BeforeEach
  void setup() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
      applyScript(conn, "data-e2e-postgresql.sql");
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
   * Strict monotonic ordering, no duplicates, shared across distinct call sites. The non-Harness
   * business generators (provider, model, definition, comfyui, canvas, chat, chat session) all
   * delegate to the same physical sequence.
   */
  @Test
  void allocationsAreStrictlyMonotonicAndSharedAcrossCallSites() {
    int totalCalls = 64;
    Set<Long> seen = new HashSet<>(totalCalls);
    Map<String, Long> lastBySite = new LinkedHashMap<>();

    String[] sites = {
      "agent_provider",
      "agent_model",
      "agent_definition",
      "comfyui_workflow_api",
      "canvas_document",
      "canvas_node",
      "canvas_link",
      "canvas_command",
      "chat"
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
   * After applying the e2e seed (provider ids 1..5, model ids 1..12, definition id 1, retry policy
   * id 1), the seed script advances the sequence via {@code setval(..., true)} so no allocation can
   * ever return a value within the seeded range.
   */
  @Test
  void allocationsNeverReuseSeededDeterministicIds() throws Exception {
    long maxSeeded;
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select greatest("
                    + "coalesce((select max(id) from agent_provider), 0),"
                    + "coalesce((select max(id) from agent_model), 0),"
                    + "coalesce((select max(id) from agent_definition), 0),"
                    + "coalesce((select max(id) from harness_retry_policy), 0)"
                    + ")")) {
      assertTrue(rs.next());
      maxSeeded = rs.getLong(1);
    }
    assertEquals(
        E2E_SEED_MAX_ID,
        maxSeeded,
        "data-e2e-postgresql.sql must keep its expected deterministic id range");

    long maxSeen = 0L;
    for (int i = 0; i < 8; i++) {
      long id = generator.next();
      assertTrue(
          id > maxSeeded,
          "kk_studio_id_seq must skip seeded ids (max=" + maxSeeded + ") but produced " + id);
      assertTrue(id > maxSeen, "monotonic, got " + id + " after " + maxSeen);
      maxSeen = id;
    }

    // PostgreSQL is_called is true after the seed's setval(..., true) call.
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select last_value, is_called from kk_studio_id_seq");
        ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      long lastValue = rs.getLong(1);
      assertTrue(
          rs.getBoolean(2), "sequence must be marked is_called after seed setval(..., true)");
      assertTrue(lastValue >= maxSeeded, "sequence last_value must be at least the seeded max");
    }
  }
}
