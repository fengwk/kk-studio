package fun.fengwk.kkstudio.platform.storage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code storage_blob} 可空列（{@code width}/{@code height}/{@code duration_ms}）在真实 PostgreSQL
 * 上的映射回归：可空列必须保持 NULL 语义，不能通过 {@code getLong} 退化为 0。
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
    assertThat(repository.retainOrInsertActive(blob)).isEqualTo(blob.getId());

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
    assertThat(repository.retainOrInsertActive(blob)).isEqualTo(blob.getId());

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
    assertThat(repository.retainOrInsertActive(blob)).isEqualTo(blob.getId());

    StorageBlob loaded = repository.getById(blob.getId());
    assertThat(loaded.getWidth()).isEqualTo(640L);
    assertThat(loaded.getHeight()).isEqualTo(480L);
    assertThat(loaded.getDurationMs()).isNull();
  }

  @Test
  void conflictingCandidateRetainsExistingBlobWithoutReplacingMediaFacts() {
    // 冲突与 retain 是同一语句，不能覆盖胜方的媒体事实。
    StorageBlob first = blob(4);
    first.setWidth(640L);
    first.setHeight(480L);
    StorageBlob second = blob(4);
    second.setWidth(1280L);
    second.setHeight(720L);
    assertThat(repository.retainOrInsertActive(first)).isEqualTo(first.getId());
    assertThat(repository.retainOrInsertActive(second)).isEqualTo(first.getId());
    StorageBlob retained = repository.getById(first.getId());
    assertThat(retained.getRefCount()).isEqualTo(2);
    assertThat(retained.getWidth()).isEqualTo(640L);
    assertThat(repository.getById(second.getId())).isNull();
  }

  @Test
  void candidateWaitsForLastReleaseAndInsertsAfterOldBlobBecomesDeleting() throws Exception {
    // 最后一次 release 未提交时，去重插入必须等待数据库裁决，而不是读到旧快照后丢失引用。
    StorageBlob old = blob(5);
    StorageBlob candidate = blob(5);
    repository.retainOrInsertActive(old);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection connection = PostgresSchemaSupport.newConnection()) {
      connection.setAutoCommit(false);
      int backendPid;
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
        result.next();
        backendPid = result.getInt(1);
      }
      try (PreparedStatement release =
          connection.prepareStatement(
              "update storage_blob set ref_count = 0, state = 'DELETING' where id = ?")) {
        release.setObject(1, old.getId());
        assertThat(release.executeUpdate()).isEqualTo(1);
      }
      Future<UUID> resolved = executor.submit(() -> repository.retainOrInsertActive(candidate));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean blocked = false;
      while (System.nanoTime() < deadline && !blocked) {
        Integer waiting =
            jdbcClient
                .sql(
                    "select count(*) from pg_stat_activity where :pid = any(pg_blocking_pids(pid))")
                .param("pid", backendPid)
                .query(Integer.class)
                .single();
        blocked = waiting > 0;
        if (!blocked) {
          Thread.sleep(20);
        }
      }
      assertThat(blocked).as("candidate is waiting on the uncommitted release").isTrue();
      connection.commit();
      assertThat(resolved.get(10, TimeUnit.SECONDS)).isEqualTo(candidate.getId());
      assertThat(repository.getById(old.getId()).getState()).isEqualTo(StorageBlobState.DELETING);
      assertThat(repository.getById(candidate.getId()).getRefCount()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void durableImageModelViewRoundTripsAndIsUniquePerSourceAndTier() {
    StorageBlob source = blob(6);
    StorageBlob view = blob(7);
    assertThat(repository.retainOrInsertActive(source)).isEqualTo(source.getId());
    assertThat(repository.retainOrInsertActive(view)).isEqualTo(view.getId());

    StorageBlobRepository.ImageModelView modelView =
        new StorageBlobRepository.ImageModelView(
            source.getId(), ImageInputTier.P720, view.getId(), 4000, 2000, 1280, 640);

    assertThat(repository.insertImageModelView(modelView)).isTrue();
    assertThat(repository.getImageModelView(source.getId(), ImageInputTier.P720))
        .isEqualTo(modelView);
    assertThat(repository.insertImageModelView(modelView)).isFalse();
    assertThat(repository.deleteImageModelViews(source.getId())).containsExactly(view.getId());
    assertThat(repository.getImageModelView(source.getId(), ImageInputTier.P720)).isNull();
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
