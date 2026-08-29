package fun.fengwk.kkstudio.platform.harness.contributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
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
    EntryPath path = rootPathWithCustom();
    UUID entryId = new UUID(0L, 11L);
    when(transaction.loadEntryPath(entryId)).thenReturn(path);
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

    verify(transaction, times(2)).loadEntryPath(entryId);
    assertThrows(NullPointerException.class, () -> loader.load(null, "goal"));
    assertThrows(NullPointerException.class, () -> loader.load(entryId, (String) null));
    assertThrows(NullPointerException.class, () -> new DatabaseContributorBranchViewLoader(null));
  }

  private static EntryPath rootPathWithCustom() {
    UUID id1 = new UUID(0L, 1L);
    UUID id2 = new UUID(0L, 2L);
    Entry root =
        new Entry(
            id1,
            id1,
            null,
            new RootPayload(
                new BranchSettings(
                    null, "assistant", new ModelSelection("provider", "model", "default"))),
            Instant.parse("2026-01-01T00:00:00Z"));
    Entry custom =
        new Entry(
            id2,
            id1,
            id1,
            new CustomEntryPayload("goal", "goal.state", 1, "{\"state\":\"ok\"}"),
            Instant.parse("2026-01-01T00:00:01Z"));
    return new EntryPath(List.of(root, custom));
  }
}
