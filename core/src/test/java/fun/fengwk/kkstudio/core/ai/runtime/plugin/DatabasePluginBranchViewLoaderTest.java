package fun.fengwk.kkstudio.core.ai.runtime.plugin;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.plugin.BranchView;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

class DatabasePluginBranchViewLoaderTest {

  @Test
  void loadsTheFrozenEntryPathInsideAStoreTransaction() {
    HarnessStore store = mock(HarnessStore.class);
    HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    EntryPath path = rootPath();
    when(transaction.loadEntryPath(11L)).thenReturn(path);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    DatabasePluginBranchViewLoader loader = new DatabasePluginBranchViewLoader(store);

    BranchView view = loader.load(11L);

    assertSame(path, view.path());
    verify(transaction).loadEntryPath(11L);
    assertThrows(IllegalArgumentException.class, () -> loader.load(0L));
    assertThrows(NullPointerException.class, () -> new DatabasePluginBranchViewLoader(null));
  }

  private static EntryPath rootPath() {
    Entry root =
        new Entry(
            1L,
            1L,
            null,
            new RootPayload(
                new BranchSettings(
                    null,
                    "assistant",
                    new ModelSelection("provider", "model", "default"),
                    "medium",
                    List.of())),
            Instant.parse("2026-01-01T00:00:00Z"));
    return new EntryPath(List.of(root));
  }
}
