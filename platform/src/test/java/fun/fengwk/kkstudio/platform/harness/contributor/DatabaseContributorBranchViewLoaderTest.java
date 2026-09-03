package fun.fengwk.kkstudio.platform.harness.contributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** 验证使用 Harness append-only Entry path 构造冻结 contributor branch view。 */
class DatabaseContributorBranchViewLoaderTest {

  @Test
  void loadsTheFrozenEntryPathInsideAStoreTransaction() {
    HarnessStore store = mock(HarnessStore.class);
    HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    UUID entryId = new UUID(0L, 11L);
    Entry customEntry = customEntry(entryId, "goal", "goal.state", "{\"state\":\"ok\"}");
    when(transaction.loadContributorCustomEntriesOnPath(entryId, "goal"))
        .thenReturn(List.of(customEntry));
    when(transaction.loadContributorCustomEntriesOnPath(entryId, "other")).thenReturn(List.of());
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    DatabaseContributorBranchViewLoader loader = new DatabaseContributorBranchViewLoader(store);

    BranchView view = loader.load(entryId, "goal");

    assertEquals(
        List.of(new CustomStateSnapshot(1, "{\"state\":\"ok\"}")),
        view.customEntries("goal.state"));
    assertEquals(
        Optional.of(new CustomStateSnapshot(1, "{\"state\":\"ok\"}")),
        view.latestCustomEntry("goal.state"));
    assertTrue(view.customEntries("other.type").isEmpty());

    // Scoped to "goal", other contributor's entry is not visible
    BranchView otherView = loader.load(entryId, new ContributorId("other"));
    assertTrue(otherView.customEntries("goal.state").isEmpty());

    verify(transaction, never()).loadEntryPath(any());
    verify(transaction).loadContributorCustomEntriesOnPath(entryId, "goal");
    verify(transaction).loadContributorCustomEntriesOnPath(entryId, "other");
    assertThrows(NullPointerException.class, () -> loader.load(null, "goal"));
    assertThrows(NullPointerException.class, () -> loader.load(entryId, (String) null));
    assertThrows(NullPointerException.class, () -> new DatabaseContributorBranchViewLoader(null));
  }

  private static Entry customEntry(
      UUID id, String contributorId, String customType, String dataJson) {
    UUID sessionId = new UUID(0L, 1L);
    UUID parentId = new UUID(0L, 2L);
    return new Entry(
        id,
        sessionId,
        parentId,
        new CustomEntryPayload(contributorId, customType, 1, dataJson),
        Instant.parse("2026-01-01T00:00:01Z"));
  }
}
