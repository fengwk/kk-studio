package fun.fengwk.kkstudio.harness.builtin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.spi.store.EntryStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assistant result metadata must record the frozen variant effort used by the completed invocation.
 */
class ModelOutcomeAppenderTest {
  private static final UUID SESSION_ID = id(1);
  private static final UUID THREAD_ID = id(2);
  private static final UUID TURN_START_ID = id(3);
  private static final UUID REQUEST_HEAD_ID = id(4);
  private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings(
          "agent", BuiltinExecution.of(ModelSelection.model("model", "default")), null);

  @Test
  void appendsEffortFromDurableGroupMemberAndStoresOffAsNull() {
    assertEquals("high", appendedEffort(groupInvocation()));
    assertNull(appendedEffort(invocation(variant("off", "off"), null)));
  }

  private static String appendedEffort(ModelInvocation invocation) {
    BuiltinStore.Transaction tx = mock(BuiltinStore.Transaction.class);
    EntryStore entries = mock(EntryStore.class);
    when(tx.entries()).thenReturn(entries);
    when(tx.nextId()).thenReturn(id(5), id(6));
    EntryPath path = requestPath();

    new ModelOutcomeAppender().appendModel(tx, path, invocation, NOW, false);

    ArgumentCaptor<Entry> inserted = ArgumentCaptor.forClass(Entry.class);
    verify(entries, times(2)).insert(inserted.capture());
    return ((MessagePayload) inserted.getAllValues().getFirst().payload())
        .assistantMetadata()
        .effort();
  }

  private static ModelInvocation groupInvocation() {
    ModelDescriptor first = model("first");
    ModelDescriptor second = model("second");
    ModelGroupExecution group =
        new ModelGroupExecution(
            "group",
            1,
            List.of(
                new ModelGroupMemberExecution(ProviderType.OPENAI, first, variant("low", "low")),
                new ModelGroupMemberExecution(
                    ProviderType.OPENAI, second, variant("high", "high"))),
            Set.of(),
            0);
    ModelRequestSpec request =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            model("group"),
            variant("default", null),
            1024,
            "system",
            List.of(),
            List.of(),
            group,
            Map.of("system", "system"));
    return invocation(request, new ModelGroupProgress("group", 1, 1, 0));
  }

  private static ModelInvocation invocation(ModelVariant variant, ModelGroupProgress progress) {
    return invocation(
        new ModelRequestSpec(
            ProviderType.OPENAI,
            model("model"),
            variant,
            1024,
            "system",
            List.of(),
            List.of(),
            null,
            Map.of("system", "system")),
        progress);
  }

  private static ModelInvocation invocation(ModelRequestSpec request, ModelGroupProgress progress) {
    return new ModelInvocation(
        id(7),
        THREAD_ID,
        TURN_START_ID,
        REQUEST_HEAD_ID,
        request,
        ModelInvocationStatus.SUCCEEDED,
        1,
        null,
        new ProviderResponse(
            "complete",
            null,
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 1, 0, 0, Map.of(), null, null),
            "request-id",
            null,
            null),
        null,
        null,
        List.of(),
        NOW,
        NOW,
        progress,
        NOW);
  }

  private static EntryPath requestPath() {
    Entry root =
        new Entry(id(10), SESSION_ID, null, new RootPayload(SETTINGS), NOW.minusSeconds(3));
    Entry turnStart =
        new Entry(
            TURN_START_ID,
            SESSION_ID,
            root.id(),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID),
            NOW.minusSeconds(2));
    Entry user =
        new Entry(
            REQUEST_HEAD_ID,
            SESSION_ID,
            turnStart.id(),
            new MessagePayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("prompt"))),
                null,
                null),
            NOW.minusSeconds(1));
    return new EntryPath(List.of(root, turnStart, user));
  }

  private static ModelDescriptor model(String name) {
    return new ModelDescriptor("provider", name, name, Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ModelVariant variant(String id, String effort) {
    return new ModelVariant(id, effort, PromptCacheRetention.NONE, null);
  }

  private static UUID id(long value) {
    return new UUID(0, value);
  }
}
