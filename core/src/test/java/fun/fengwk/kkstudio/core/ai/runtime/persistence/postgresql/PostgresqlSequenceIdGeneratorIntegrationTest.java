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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 端到端覆盖：通过 {@link PostgresqlSequenceIdGenerator} 串联起来的 PostgreSQL {@code kk_studio_id_seq} 序列。
 *
 * <p>继承 {@link PostgresSchemaSupport}，因此会启动一个真实的 {@code postgres:17-alpine} Testcontainers 实例，并可通过
 * {@link #newConnection()} 直接拿到 JDBC 句柄。Docker 必须可用；不可用时本测试失败而不是跳过。这里使用的 {@link SequenceMapper} 是基于
 * JDBC 的 JDK lambda，而非 MyBatis 代理；因此测试执行的是生产 mapper 实际发出的同一条 SQL，而不是某个假实现。
 */
class PostgresqlSequenceIdGeneratorIntegrationTest extends PostgresSchemaSupport {

  /** 常量直接照搬自 {@link SequenceMapper#nextValue()}，以便任何 annotation 与 JDBC 调用之间的漂移都能被显式捕获。 */
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

  /** 锁定生产 MyBatis SQL：任何漂移都会使本测试失败。 */
  @Test
  void sequenceMapperSelectAnnotationMatchesProductionSql() throws NoSuchMethodException {
    Method nextValue = SequenceMapper.class.getMethod("nextValue");
    Select select = nextValue.getAnnotation(Select.class);
    assertNotNull(select, "@Select must be declared on SequenceMapper.nextValue");
    assertEquals(1, select.value().length, "@Select must declare exactly one statement");
    assertEquals(EXPECTED_SELECTION_SQL, select.value()[0]);
  }

  /** 每次分配都必须严格为正。 */
  @Test
  void allocationsAreStrictlyPositive() {
    for (int i = 0; i < 20; i++) {
      long id = generator.next();
      assertTrue(id > 0, "kk_studio_id_seq must produce positive ids, got " + id);
    }
  }

  /**
   * 严格的单调递增、无重复，且在不同调用方之间共享。生成的 Canvas 持久化业务实体都委托给同一个物理序列；catalog
   * 标识就是名称，因此不会消耗该序列。Chat/Comfy/Harness 实体已迁移到应用侧 UUID，不再使用该序列。
   */
  @Test
  void allocationsAreStrictlyMonotonicAndSharedAcrossCallSites() {
    int totalCalls = 64;
    Set<Long> seen = new HashSet<>(totalCalls);
    Map<String, Long> lastBySite = new LinkedHashMap<>();

    String[] sites = {
      "canvas_document", "canvas_group", "canvas_node", "canvas_resource", "canvas_upload"
    };

    long previous = 0L;
    for (int i = 0; i < totalCalls; i++) {
      String site = sites[i % sites.length];
      // 每次迭代都对生成器进行往返调用，使 lambda 驱动的单调性可见；
      // 下面的 per-site 簿记与生产接线方式一致：每个业务生成器推进同一个物理序列。
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
}
