package fun.fengwk.kkstudio.platform.storage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * {@code storage_blob} 可空列（{@code width}/{@code height}/{@code duration_ms}）在真实 PostgreSQL 上的映射回归：旧
 * MyBatis {@code LongTypeHandler} 把 NULL 原样映射为 null，迁移到 JDBC 后必须保持该语义而不是通过 {@code getLong} 退化为 0。
 *
 * <p>直接驱动仓库的 {@code JdbcClient} 语句而不加载 Spring 上下文，因此该回归只依赖被测 SQL 与行映射本身。
 */
class StorageBlobRepositoryIntegrationTest {

  private static JdbcClient jdbcClient;

  private StorageBlobRepository repository;

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
    repository = new StorageBlobRepository(jdbcClient);
  }

  /** 三个可空列全为 NULL：按主键与按 hash/size 读取都必须保持 null。 */
  @Test
  void nullableColumnsStayNull() {
    StorageBlob blob = blob(1);
    blob.setWidth(null);
    blob.setHeight(null);
    blob.setDurationMs(null);
    assertThat(repository.insertActiveCandidate(blob)).isTrue();

    StorageBlob byId = repository.getById(blob.getId());
    assertThat(byId.getWidth()).isNull();
    assertThat(byId.getHeight()).isNull();
    assertThat(byId.getDurationMs()).isNull();

    StorageBlob byHash = repository.getActiveByHashAndSize(blob.getSha256(), blob.getSizeBytes());
    assertThat(byHash.getWidth()).isNull();
    assertThat(byHash.getHeight()).isNull();
    assertThat(byHash.getDurationMs()).isNull();
  }

  /** 尺寸与时长都有值：读回原值，确认可空处理没有破坏非空路径。 */
  @Test
  void presentDimensionsAndDurationRoundTrip() {
    StorageBlob blob = blob(2);
    blob.setWidth(1920L);
    blob.setHeight(1080L);
    blob.setDurationMs(5000L);
    assertThat(repository.insertActiveCandidate(blob)).isTrue();

    StorageBlob loaded = repository.getById(blob.getId());
    assertThat(loaded.getWidth()).isEqualTo(1920L);
    assertThat(loaded.getHeight()).isEqualTo(1080L);
    assertThat(loaded.getDurationMs()).isEqualTo(5000L);
  }

  /** 有尺寸无时长是合法组合：尺寸保留，只有 duration_ms 为 null。 */
  @Test
  void durationMayBeNullWhileDimensionsPresent() {
    StorageBlob blob = blob(3);
    blob.setWidth(640L);
    blob.setHeight(480L);
    blob.setDurationMs(null);
    assertThat(repository.insertActiveCandidate(blob)).isTrue();

    StorageBlob loaded = repository.getById(blob.getId());
    assertThat(loaded.getWidth()).isEqualTo(640L);
    assertThat(loaded.getHeight()).isEqualTo(480L);
    assertThat(loaded.getDurationMs()).isNull();
  }

  private StorageBlob blob(int seed) {
    StorageBlob blob = new StorageBlob();
    blob.setId(UUID.randomUUID());
    blob.setSha256(String.format("%064d", seed));
    blob.setSizeBytes(1024L);
    blob.setMediaType("image/png");
    blob.setRefCount(1L);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }
}
