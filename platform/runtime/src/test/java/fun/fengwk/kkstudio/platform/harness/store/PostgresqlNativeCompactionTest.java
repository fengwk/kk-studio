package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.NativeCompactionPayload;

import java.time.Instant;
import java.util.UUID;

class PostgresqlNativeCompactionTest {

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
  }

  @AfterEach
  void tearDown() {
    PostgresqlHarnessStoreFixture.reset();
  }

  @Test
  void schemaAcceptsNativeAudit() {
    var store = PostgresqlHarnessStoreFixture.create();
    var baseline = seedThreadBaseline(store);
    var payload = new NativeCompactionPayload("auto", null, 0L, 12L, null, "native", null, null);
    Entry audit =
        new Entry(
            UUID.randomUUID(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            payload,
            Instant.ofEpochMilli(1000));
    store.transaction(
        tx -> {
          tx.entries().insert(audit);
          return null;
        });
    var path = store.transaction(tx -> tx.entries().loadPath(audit.id()));
    assertEquals(payload, path.head().payload());
    assertEquals(baseline.rootEntryId(), path.headIgnoringTrailingControlEntries().id());
  }
}
