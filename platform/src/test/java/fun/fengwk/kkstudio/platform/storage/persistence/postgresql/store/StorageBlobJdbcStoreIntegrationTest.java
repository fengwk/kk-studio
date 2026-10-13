package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model.StorageBlobDO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * {@code storage_blob} 可空列（{@code width}/{@code height}/{@code duration_ms}）在真实 PostgreSQL 上的映射回归：旧
 * MyBatis {@code LongTypeHandler} 把 NULL 原样映射为 null，迁移到 JDBC 后必须保持该语义而不是通过 {@code getLong} 退化为 0。
 *
 * <p>直接驱动 store 的 {@code JdbcClient} 语句而不加载 Spring 上下文，因此该回归只依赖被测 SQL 与行映射本身。
 */
class StorageBlobJdbcStoreIntegrationTest {

  private static JdbcClient jdbcClient;

  private StorageBlobJdbcStore store;

  @BeforeAll
  static void beforeAll() {
    jdbcClient =
        JdbcClient.create(
            new SimpleDriverDataSource(
                new Driver(),
                PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
                PostgresSchemaSupport.POSTGRES.getUsername(),
                PostgresSchemaSupport.POSTGRES.getPassword()));
  }

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    store = new StorageBlobJdbcStore(jdbcClient);
  }

  /** 三个可空列全为 NULL：按主键与按 hash/size 读取都必须保持 null。 */
  @Test
  void nullableColumnsStayNull() {
    StorageBlobDO blob = blob(1);
    blob.setWidth(null);
    blob.setHeight(null);
    blob.setDurationMs(null);
    assertThat(store.insertActiveCandidate(blob)).isEqualTo(1);

    StorageBlobDO byId = store.getById(blob.getId());
    assertThat(byId.getWidth()).isNull();
    assertThat(byId.getHeight()).isNull();
    assertThat(byId.getDurationMs()).isNull();

    StorageBlobDO byHash = store.getActiveByHashAndSize(blob.getSha256(), blob.getSizeBytes());
    assertThat(byHash.getWidth()).isNull();
    assertThat(byHash.getHeight()).isNull();
    assertThat(byHash.getDurationMs()).isNull();
  }

  /** 尺寸与时长都有值：读回原值，确认可空处理没有破坏非空路径。 */
  @Test
  void presentDimensionsAndDurationRoundTrip() {
    StorageBlobDO blob = blob(2);
    blob.setWidth(1920L);
    blob.setHeight(1080L);
    blob.setDurationMs(5000L);
    assertThat(store.insertActiveCandidate(blob)).isEqualTo(1);

    StorageBlobDO loaded = store.getById(blob.getId());
    assertThat(loaded.getWidth()).isEqualTo(1920L);
    assertThat(loaded.getHeight()).isEqualTo(1080L);
    assertThat(loaded.getDurationMs()).isEqualTo(5000L);
  }

  /** 有尺寸无时长是合法组合：尺寸保留，只有 duration_ms 为 null。 */
  @Test
  void durationMayBeNullWhileDimensionsPresent() {
    StorageBlobDO blob = blob(3);
    blob.setWidth(640L);
    blob.setHeight(480L);
    blob.setDurationMs(null);
    assertThat(store.insertActiveCandidate(blob)).isEqualTo(1);

    StorageBlobDO loaded = store.getById(blob.getId());
    assertThat(loaded.getWidth()).isEqualTo(640L);
    assertThat(loaded.getHeight()).isEqualTo(480L);
    assertThat(loaded.getDurationMs()).isNull();
  }

  private StorageBlobDO blob(int seed) {
    StorageBlobDO blob = new StorageBlobDO();
    blob.setId(UUID.randomUUID());
    blob.setSha256(String.format("%064d", seed));
    blob.setSizeBytes(1024L);
    blob.setMediaType("image/png");
    blob.setRefCount(1L);
    return blob;
  }
}
