package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.ForkMode;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 真实 PostgreSQL 上的历史 JSON 升级消费证据：转换结果写入实际列后，生产 {@link PostgresqlHarnessStore} 与真实 {@link
 * HarnessRuntime} 能读回，并且真实 session fork 会原样复制 tool metadata、人工输入回执、replay 与精确数值。
 */
class LegacyHarnessHistoryJsonPostgresIntegrationTest {

  private static final Instant T0 = Instant.ofEpochMilli(1000);
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final String CREATION_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static final BranchSettings SETTINGS =
      new BranchSettings(
          "default-assistant", new ModelSelection("openai", "gpt-5", "default"), null);

  private DataSource dataSource;
  private HarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    store =
        new PostgresqlHarnessStore(
            dataSource, new DataSourceTransactionManager(dataSource), UUID::randomUUID);
    runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T0, ZoneOffset.UTC),
            (threadId, path, preparation) -> null,
            () -> CompactionConfig.DEFAULT);
  }

  /** 真实 store 读回写入列的转换结果，真实 session fork 复制后逐字段保留 metadata / receipt / replay 与精确数值。 */
  @Test
  void readsUpgradedHistoryAndForkCopiesTheExactFacts() {
    JsonNode legacyToolMessage = read(fixture("legacy-tool-result-message-with-receipt.json"));
    JsonNode legacyMetadata = legacyToolMessage.get("toolResultMetadata");
    UUID assistantId = UUID.fromString(legacyMetadata.get("assistantEntryId").stringValue());
    UUID invocationId = UUID.fromString(legacyMetadata.get("invocationId").stringValue());
    UUID submissionId =
        UUID.fromString(legacyMetadata.get("inputReceipt").get("submissionId").stringValue());
    String toolCallId = legacyMetadata.get("toolCallId").stringValue();
    Instant acceptedAt =
        Instant.parse(legacyMetadata.get("inputReceipt").get("acceptedAt").stringValue());

    UUID sessionId = id(1);
    UUID rootId = id(2);
    UUID turnStartId = id(3);
    UUID userId = id(4);
    UUID toolId = id(5);
    UUID turnEndId = id(6);
    UUID threadId = id(7);

    // 用当前 API 建合法父链；tool 结果先写一份不含回执的占位，随后把旧 JSON 的转换结果写入实际列。
    store.transaction(
        tx -> {
          tx.insertSession(new Session(sessionId, "legacy-session", T0));
          tx.insertEntry(new Entry(rootId, sessionId, null, new RootPayload(SETTINGS), T0));
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  sessionId,
                  rootId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT,
                      SETTINGS,
                      threadId,
                      CONTEXT_WINDOW,
                      MAX_OUTPUT_TOKENS,
                      null),
                  T0.plusMillis(1)));
          tx.insertEntry(
              new Entry(
                  userId,
                  sessionId,
                  turnStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                      null,
                      null),
                  T0.plusMillis(2)));
          tx.insertEntry(
              new Entry(
                  assistantId,
                  sessionId,
                  userId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new ToolCallMessageContent(toolCallId, "ask_user", "ask_user", "{}"),
                              new TextMessageContent("asking"))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE,
                          new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L)),
                      null),
                  T0.plusMillis(3)));
          tx.insertEntry(
              new Entry(
                  toolId,
                  sessionId,
                  assistantId,
                  placeholderToolResult(toolCallId, invocationId, assistantId),
                  T0.plusMillis(4)));
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  toolId,
                  new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T0.plusMillis(5)));
          tx.insertThread(thread(threadId, sessionId, turnEndId));
          return null;
        });

    String legacyReplay = fixture("legacy-provider-replay-openai-response.json");
    String convertedToolPayload =
        LegacyHarnessHistoryJson.entryPayload(
            EntryType.MESSAGE, fixture("legacy-tool-result-message-with-receipt.json"));
    String convertedReplay = LegacyHarnessHistoryJson.providerReplayState(legacyReplay);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertEquals(
        1,
        jdbc.update(
            "update harness_entry set payload = cast(? as jsonb) where id = ?",
            convertedToolPayload,
            toolId));
    assertEquals(
        1,
        jdbc.update(
            "update harness_entry set provider_replay_state = cast(? as jsonb) where id = ?",
            convertedReplay,
            assistantId));

    EntryPath source = store.transaction(tx -> tx.loadEntryPath(turnEndId));
    assertEquals(6, source.entries().size());
    assertToolResult(source, assistantId, invocationId, toolCallId, submissionId, acceptedAt);
    assertReplay(source, read(legacyReplay));
    assertEquals(6, runtime.getThreadSnapshot(threadId).entryPath().entries().size());
    assertEquals(6, runtime.getSessionEntries(sessionId).size());

    UUID forkedSessionId = id(11);
    UUID forkedThreadId = id(12);
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewForkedSession(
                    threadId, turnEndId, forkedSessionId, forkedThreadId, false),
                List.of(userMessage("fork"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());

    EntryPath forked = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
    assertEquals(7, forked.entries().size());
    ForkPayload fork = assertInstanceOf(ForkPayload.class, forked.head().payload());
    assertEquals(ForkMode.SESSION, fork.mode());
    assertEquals(threadId, fork.sourceThreadId());
    assertEquals(turnEndId, fork.sourceEntryId());

    UUID forkedAssistantId =
        forked.entries().stream()
            .filter(
                entry ->
                    entry.payload() instanceof MessagePayload message
                        && message.assistantMetadata() != null)
            .map(Entry::id)
            .findFirst()
            .orElseThrow();
    assertNotEquals(assistantId, forkedAssistantId);
    assertToolResult(forked, forkedAssistantId, invocationId, toolCallId, submissionId, acceptedAt);
    assertReplay(forked, read(legacyReplay));

    // 源历史绝不被 fork 改写。
    assertToolResult(
        store.transaction(tx -> tx.loadEntryPath(turnEndId)),
        assistantId,
        invocationId,
        toolCallId,
        submissionId,
        acceptedAt);
  }

  /** 复制的 TOOL metadata 逐字段保留；assistantEntryId 已被重写到新身份；回执是真实值而非仅非 null。 */
  private static void assertToolResult(
      EntryPath path,
      UUID expectedAssistantId,
      UUID invocationId,
      String toolCallId,
      UUID submissionId,
      Instant acceptedAt) {
    ToolResultMetadata metadata =
        path.entries().stream()
            .filter(
                entry ->
                    entry.payload() instanceof MessagePayload message
                        && message.toolResultMetadata() != null)
            .map(entry -> ((MessagePayload) entry.payload()).toolResultMetadata())
            .findFirst()
            .orElseThrow();
    assertEquals(invocationId, metadata.invocationId());
    assertEquals(expectedAssistantId, metadata.assistantEntryId());
    assertEquals(toolCallId, metadata.toolCallId());
    assertEquals(0, metadata.callIndex());
    assertEquals(ToolResultStatus.SUCCEEDED, metadata.status());
    assertFalse(metadata.synthetic());
    assertNull(metadata.reason());
    assertNull(metadata.binding());
    ToolInputReceipt receipt = metadata.inputReceipt();
    assertNotNull(receipt);
    assertEquals(submissionId, receipt.submissionId());
    assertEquals("operator", receipt.actor());
    assertEquals(acceptedAt, receipt.acceptedAt());
  }

  /** 复制 / 读回的 ASSISTANT replay 保留 format、affinity 与 opaque payload，且精确数值不因 jsonb 往返失真。 */
  private static void assertReplay(EntryPath path, JsonNode sourceReplay) {
    ProviderReplayState replay =
        path.entries().stream()
            .map(Entry::providerReplayState)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow();
    assertEquals(ProviderReplayFormat.OPENAI_RESPONSES, replay.format());
    assertEquals(ProviderType.OPENAI_RESPONSES, replay.affinity().providerType());
    assertEquals("openai", replay.affinity().providerName());
    assertEquals(
        UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
        replay.affinity().connectionGenerationId());
    assertEquals("gpt-5", replay.affinity().modelId());

    JsonNode expected = sourceReplay.get("payload");
    JsonNode actual = replay.payload();
    assertEquals("openai_response", actual.get("request_spec").get("type").stringValue());
    assertEquals(
        expected.get("opaqueToken").stringValue(), actual.get("opaqueToken").stringValue());
    assertEquals(
        new BigInteger("123456789012345678901234567890"),
        actual.get("bigInteger").bigIntegerValue());
    assertEquals(
        BigInteger.valueOf(9007199254740993L), actual.get("beyondDoubleInteger").bigIntegerValue());
    assertDecimal(expected.get("longDecimal"), actual.get("longDecimal"));
    assertDecimal(expected.get("negativeExponent"), actual.get("negativeExponent"));
    assertDecimal(expected.get("positiveExponent"), actual.get("positiveExponent"));
  }

  /** 数值按精确十进制展开比较：jsonb 会把指数记法规范化，但必须逐位保留 BigInteger / BigDecimal 的精确值。 */
  private static void assertDecimal(JsonNode expected, JsonNode actual) {
    BigDecimal expectedValue = expected.decimalValue();
    BigDecimal actualValue = actual.decimalValue();
    assertEquals(0, expectedValue.compareTo(actualValue), actualValue.toString());
    assertEquals(expectedValue.toPlainString(), actualValue.toPlainString());
  }

  private static MessagePayload placeholderToolResult(
      String toolCallId, UUID invocationId, UUID assistantId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId,
            "ask_user",
            "ask_user",
            List.of(new TextMessageContent("answer accepted")),
            false,
            "{}");
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)),
        null,
        new ToolResultMetadata(
            invocationId,
            assistantId,
            toolCallId,
            0,
            ToolResultStatus.SUCCEEDED,
            false,
            null,
            null,
            null));
  }

  private static ThreadState thread(UUID threadId, UUID sessionId, UUID headEntryId) {
    return new ThreadState(
        threadId,
        sessionId,
        null,
        headEntryId,
        CREATION_HASH,
        "main",
        ThreadYoloPolicy.root(false),
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        T0,
        T0.plusMillis(5));
  }

  private static NewThreadCommand userMessage(String text) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        UUID.randomUUID());
  }

  private static UUID id(long value) {
    return new UUID(0x5a5a5a5a5a5a5a5aL, value);
  }

  private static JsonNode read(String json) {
    return JsonValues.readTree(json);
  }

  private static String fixture(String name) {
    try (InputStream in =
        LegacyHarnessHistoryJsonPostgresIntegrationTest.class.getResourceAsStream(name)) {
      assertNotNull(in, "missing fixture " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
