package fun.fengwk.kkstudio.platform.harness.contributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 验证使用 Harness append-only Entry path 构造冻结 contributor branch view。 */
class DatabaseContributorBranchViewLoaderTest {

  @Test
  void loadsTheFrozenEntryPathInsideAStoreTransaction() {
    HarnessStore store = mock(HarnessStore.class);
    HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    EntryPath path = rootPath();
    UUID entryId = new UUID(0L, 11L);
    when(transaction.loadEntryPath(entryId)).thenReturn(path);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    DatabaseContributorBranchViewLoader loader = new DatabaseContributorBranchViewLoader(store);

    BranchView view = loader.load(entryId);

    assertEquals(new BranchView(path), view);
    verify(transaction).loadEntryPath(entryId);
    assertThrows(NullPointerException.class, () -> loader.load(null));
    assertThrows(NullPointerException.class, () -> new DatabaseContributorBranchViewLoader(null));
  }

  private static EntryPath rootPath() {
    UUID id = new UUID(0L, 1L);
    Entry root =
        new Entry(
            id,
            id,
            null,
            new RootPayload(
                new BranchSettings(
                    null, "assistant", new ModelSelection("provider", "model", "default"))),
            Instant.parse("2026-01-01T00:00:00Z"));
    return new EntryPath(List.of(root));
  }
}
