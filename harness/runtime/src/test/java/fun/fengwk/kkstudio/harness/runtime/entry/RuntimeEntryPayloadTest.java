package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.session.EntryPayload;
import fun.fengwk.kkstudio.harness.kernel.session.EntryType;
import fun.fengwk.kkstudio.harness.kernel.session.SessionEntry;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * {@link RuntimeEntryPayload} 族的契约与构造校验测试。覆盖：
 *
 * <ul>
 *   <li>7 种 payload 类型的 {@link EntryType} 自报与 {@link EntryPayload} 契约；
 *   <li>Kernel {@link SessionEntry} 在 type/payload 一致性上的强制校验；
 *   <li>assistant metadata 必须与 ASSISTANT role 一致；
 *   <li>tool-call 内容与 {@link ProviderStopReason#TOOL_CALLS} 严格一致；
 *   <li>compaction 数值与 detailsJson 约束；
 *   <li>assistant error 最小快照：仅 kind + message。
 * </ul>
 */
class RuntimeEntryPayloadTest {

  private static AssistantMessageMetadata completedMetadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.COMPLETED,
        new ModelUsage(0, 0, 0, 0, 0, 0, 0),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static AssistantMessageMetadata toolCallsMetadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.TOOL_CALLS,
        new ModelUsage(0, 0, 0, 0, 0, 0, 0),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static AgentMessage systemMessage() {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("sys")));
  }

  private static AgentMessage userMessage() {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi")));
  }

  private static AgentMessage toolMessage() {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                "call-1", "search", List.of(new TextMessageContent("ok")), false, "{}")));
  }

  private static AgentMessage assistantCompleted() {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("done")));
  }

  private static AgentMessage assistantToolCalls() {
    return new AgentMessage(
        AgentMessageRole.ASSISTANT, List.of(new ToolCallMessageContent("call-1", "search", "{}")));
  }

  // ---------- ROOT ----------

  @Test
  void rootPayloadTypeIsRoot() {
    RootEntryPayload payload = new RootEntryPayload();
    assertEquals(EntryType.ROOT, payload.type());
    assertSame(EntryType.ROOT, payload.type());
  }

  @Test
  void rootEntryPayloadTypeMatchesSessionEntry() {
    SessionEntry entry =
        new SessionEntry(
            1L,
            1L,
            null,
            EntryType.ROOT,
            new RootEntryPayload(),
            Instant.parse("2025-01-01T00:00:00Z"));
    assertEquals(EntryType.ROOT, entry.type());
    assertEquals(EntryType.ROOT, entry.payload().type());
  }

  // ---------- MESSAGE ----------

  @Test
  void systemMessagePayload() {
    MessageEntryPayload payload = new MessageEntryPayload(systemMessage());
    assertEquals(EntryType.MESSAGE, payload.type());
    assertNull(payload.assistantMetadata());
  }

  @Test
  void userMessagePayload() {
    MessageEntryPayload payload = new MessageEntryPayload(userMessage());
    assertEquals(EntryType.MESSAGE, payload.type());
    assertNull(payload.assistantMetadata());
  }

  @Test
  void toolMessagePayload() {
    MessageEntryPayload payload = new MessageEntryPayload(toolMessage());
    assertEquals(EntryType.MESSAGE, payload.type());
    assertNull(payload.assistantMetadata());
  }

  @Test
  void assistantMessageWithCompletedMetadata() {
    MessageEntryPayload payload =
        new MessageEntryPayload(assistantCompleted(), completedMetadata());
    assertEquals(EntryType.MESSAGE, payload.type());
    assertEquals(ProviderStopReason.COMPLETED, payload.assistantMetadata().stopReason());
  }

  @Test
  void assistantMessageWithToolCallsMetadata() {
    MessageEntryPayload payload =
        new MessageEntryPayload(assistantToolCalls(), toolCallsMetadata());
    assertEquals(EntryType.MESSAGE, payload.type());
    assertEquals(ProviderStopReason.TOOL_CALLS, payload.assistantMetadata().stopReason());
  }

  @Test
  void assistantMetadataRequiredForAssistantMessage() {
    assertThrows(
        IllegalArgumentException.class, () -> new MessageEntryPayload(assistantCompleted(), null));
  }

  @Test
  void assistantMetadataForbiddenForNonAssistantMessage() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(userMessage(), completedMetadata()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(systemMessage(), completedMetadata()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(toolMessage(), completedMetadata()));
  }

  @Test
  void assistantToolCallsMismatchStopReasonRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(assistantToolCalls(), completedMetadata()));
  }

  @Test
  void assistantToolCallsStopReasonRequiresToolCalls() {
    AgentMessage assistantNoToolCalls = assistantCompleted();
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(assistantNoToolCalls, toolCallsMetadata()));
  }

  @Test
  void messageEntryPayloadTypeMatchesSessionEntry() {
    SessionEntry entry =
        new SessionEntry(
            2L,
            1L,
            1L,
            EntryType.MESSAGE,
            new MessageEntryPayload(assistantCompleted(), completedMetadata()),
            Instant.parse("2025-01-01T00:00:00Z"));
    assertEquals(EntryType.MESSAGE, entry.type());
    assertEquals(EntryType.MESSAGE, entry.payload().type());
  }

  @Test
  void sessionEntryRejectsPayloadTypeMismatch() {
    // Payload says MESSAGE but entry type is LABEL — must throw.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SessionEntry(
                3L,
                1L,
                1L,
                EntryType.LABEL,
                new MessageEntryPayload(userMessage()),
                Instant.parse("2025-01-01T00:00:00Z")));
  }

  // ---------- CUSTOM_MESSAGE ----------

  @Test
  void customMessagePayload() {
    CustomMessageEntryPayload payload = new CustomMessageEntryPayload(userMessage());
    assertEquals(EntryType.CUSTOM_MESSAGE, payload.type());
  }

  @Test
  void customMessageRejectsNull() {
    assertThrows(NullPointerException.class, () -> new CustomMessageEntryPayload(null));
  }

  // ---------- COMPACTION ----------

  @Test
  void compactionPayload() {
    CompactionEntryPayload payload = new CompactionEntryPayload("summary", 5L, 100, "{}");
    assertEquals(EntryType.COMPACTION, payload.type());
  }

  @Test
  void compactionRejectsBlankSummary() {
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload("", 5L, 100, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload(" ", 5L, 100, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload(null, 5L, 100, "{}"));
  }

  @Test
  void compactionRejectsNonPositiveFirstKeptEntryId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload("s", 0L, 100, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload("s", -1L, 100, "{}"));
  }

  @Test
  void compactionRejectsNegativeTokensBefore() {
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload("s", 1L, -1, "{}"));
  }

  @Test
  void compactionRejectsBlankDetailsJson() {
    assertThrows(IllegalArgumentException.class, () -> new CompactionEntryPayload("s", 1L, 0, ""));
    assertThrows(IllegalArgumentException.class, () -> new CompactionEntryPayload("s", 1L, 0, " "));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionEntryPayload("s", 1L, 0, null));
  }

  // ---------- ASSISTANT_ERROR ----------

  @Test
  void assistantErrorPayloadMinimalSnapshot() {
    ModelInvocationError error = new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom");
    AssistantErrorEntryPayload payload = new AssistantErrorEntryPayload(error);
    assertEquals(EntryType.ASSISTANT_ERROR, payload.type());
    assertEquals(ProviderErrorKind.TRANSIENT, payload.error().kind());
    assertEquals("boom", payload.error().message());
  }

  @Test
  void assistantErrorMinimalSnapshot() {
    // 最小快照结构：payload 仅含 error 字段；ModelInvocationError 仅含 kind + message。
    AssistantErrorEntryPayload payload =
        new AssistantErrorEntryPayload(
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "fatal"));
    assertEquals(
        List.of("error"),
        Arrays.stream(payload.getClass().getRecordComponents()).map(rc -> rc.getName()).toList());
    assertEquals(2, payload.error().getClass().getRecordComponents().length);
  }

  @Test
  void assistantErrorRejectsNull() {
    assertThrows(NullPointerException.class, () -> new AssistantErrorEntryPayload(null));
  }

  // ---------- LABEL ----------

  @Test
  void labelPayload() {
    LabelEntryPayload payload = new LabelEntryPayload("checkpoint");
    assertEquals(EntryType.LABEL, payload.type());
  }

  @Test
  void labelRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> new LabelEntryPayload(""));
    assertThrows(IllegalArgumentException.class, () -> new LabelEntryPayload(" "));
    assertThrows(IllegalArgumentException.class, () -> new LabelEntryPayload(null));
  }

  // ---------- BRANCH_SUMMARY ----------

  @Test
  void branchSummaryPayload() {
    BranchSummaryEntryPayload payload = new BranchSummaryEntryPayload("branch closed");
    assertEquals(EntryType.BRANCH_SUMMARY, payload.type());
  }

  @Test
  void branchSummaryRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> new BranchSummaryEntryPayload(""));
    assertThrows(IllegalArgumentException.class, () -> new BranchSummaryEntryPayload(" "));
    assertThrows(IllegalArgumentException.class, () -> new BranchSummaryEntryPayload(null));
  }

  // ---------- Sealed root sanity ----------

  /**
   * sealed root 仅承担 Session Tree 的 7 个 message-bearing payload；RUNTIME_CONFIG 由
   * RuntimeConfigSnapshot 自报。
   */
  @Test
  void sealedRootCoversSessionPayloads() {
    // EntryType 一共 8 个：RUNTIME_CONFIG 由 RuntimeConfigSnapshot 直接实现，不在本 sealed root。
    long sessionTypes =
        Arrays.stream(EntryType.values()).filter(t -> t != EntryType.RUNTIME_CONFIG).count();
    assertEquals(7, sessionTypes);
    assertTrue(RuntimeEntryPayload.class.isSealed());
    assertEquals(7, RuntimeEntryPayload.class.getPermittedSubclasses().length);
  }

  @Test
  void allPayloadsAreDistinct() {
    RuntimeEntryPayload root = new RootEntryPayload();
    RuntimeEntryPayload msg = new MessageEntryPayload(userMessage());
    RuntimeEntryPayload custom = new CustomMessageEntryPayload(userMessage());
    RuntimeEntryPayload compaction = new CompactionEntryPayload("s", 1L, 0, "{}");
    RuntimeEntryPayload error =
        new AssistantErrorEntryPayload(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "x"));
    RuntimeEntryPayload label = new LabelEntryPayload("x");
    RuntimeEntryPayload branch = new BranchSummaryEntryPayload("x");

    // 不同 payload 自身 .equals 仅按 record 字段比较，这里只校验 type 互不相同。
    assertNotEquals(root.type(), msg.type());
    assertNotEquals(msg.type(), custom.type());
    assertNotEquals(msg.type(), compaction.type());
    assertNotEquals(msg.type(), error.type());
    assertNotEquals(label.type(), branch.type());
  }
}
