package fun.fengwk.kkstudio.core.ai.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.observability.service.impl.HarnessObservabilityQueryServiceImpl;
import fun.fengwk.kkstudio.core.ai.runtime.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.thread.command.TestRuntimeConfigs;
import fun.fengwk.kkstudio.core.ai.runtime.thread.command.TestThreads;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * final PostgreSQL snapshot-first query 面：session/path/derived status/input/invocation/interaction。
 */
class PostgresqlHarnessQueryServiceIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final ToolDescriptorJsonCodec DESCRIPTOR_CODEC = new ToolDescriptorJsonCodec();
  private static final RuntimeConfigJsonCodec RUNTIME_CONFIG_CODEC = new RuntimeConfigJsonCodec();
  private static final String TOOL_DESCRIPTOR_JSON =
      DESCRIPTOR_CODEC.encode(
          new ToolDescriptor(
              "bash",
              "1",
              "run",
              "bash",
              new ToolParamsSchema("", Map.of(), Set.of(), false),
              ToolSideEffect.READ_ONLY,
              Duration.ofSeconds(1)));

  @Autowired private ThreadCommandTransactions commandTransactions;
  @Autowired private HarnessSessionQueryService sessionQueryService;
  @Autowired private HarnessThreadQueryService threadQueryService;
  @Autowired private HarnessObservabilityQueryServiceImpl observabilityQueryService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void sessionIsBootstrappedByThreadCommandWithoutChildSessions() {
    TestThreads.Bootstrapped boot =
        TestThreads.bootstrap(commandTransactions, "bootstrap-only", NOW);
    String sessionId = Long.toString(boot.sessionId());

    HarnessSessionDTO loaded = sessionQueryService.getSession(sessionId);
    assertEquals("bootstrap-only", loaded.getTitle());
    assertNotNull(loaded.getCreateTime());
    assertNotNull(loaded.getUpdateTime());
    assertTrue(
        sessionQueryService.listSessions().stream()
            .anyMatch(s -> sessionId.equals(s.getSessionId())));
  }

  @Test
  void updateTimeIsDerivedFromLatestEntryCreatedAt() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(commandTransactions, "update-time", NOW);
    String sessionId = Long.toString(boot.sessionId());

    HarnessSessionDTO baseline = sessionQueryService.getSession(sessionId);
    assertEquals(
        baseline.getCreateTime(),
        baseline.getUpdateTime(),
        "Session with no extra Entries reports updateTime == createTime");

    long laterEntryId = boot.sessionId() + 9_000_000L;
    OffsetDateTime later = OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)",
        laterEntryId,
        boot.sessionId(),
        boot.configEntryId(),
        later);

    HarnessSessionDTO updated = sessionQueryService.getSession(sessionId);
    assertEquals(
        later.toInstant(),
        updated.getUpdateTime().toInstant(ZoneOffset.UTC),
        "derived updateTime must advance with the latest Entry");
    assertEquals(
        baseline.getCreateTime(),
        updated.getCreateTime(),
        "Session createTime must remain immutable");

    // Locate this session in listSessions() and assert neighbour ordering around it.
    List<HarnessSessionDTO> ordered = sessionQueryService.listSessions();
    int idx = -1;
    for (int i = 0; i < ordered.size(); i++) {
      if (sessionId.equals(ordered.get(i).getSessionId())) {
        idx = i;
        break;
      }
    }
    assertTrue(idx >= 0, "the bootstrapped session must appear in listSessions()");
    if (idx > 0) {
      assertTrue(
          !ordered.get(idx - 1).getUpdateTime().isAfter(ordered.get(idx).getUpdateTime()),
          "listSessions must be non-increasing by derived updateTime");
    }
    if (idx + 1 < ordered.size()) {
      assertTrue(
          !ordered.get(idx).getUpdateTime().isBefore(ordered.get(idx + 1).getUpdateTime()),
          "listSessions must be non-increasing by derived updateTime");
    }
  }

  @Test
  void entryProjectionContainsTreeFieldsAndOmitsSessionId() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(commandTransactions, "entries", NOW);
    long messageEntryId = boot.configEntryId() + 5;
    OffsetDateTime at = OffsetDateTime.ofInstant(NOW.plusSeconds(2), ZoneOffset.UTC);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', '{\"k\":1}'::jsonb, ?)",
        messageEntryId,
        boot.sessionId(),
        boot.configEntryId(),
        at);

    List<HarnessSessionEntryDTO> entries =
        sessionQueryService.listEntries(Long.toString(boot.sessionId()));
    assertTrue(
        entries.size() >= 2,
        "bootstrap bundle contributes ROOT + RUNTIME_CONFIG; an extra MESSAGE makes at least 3");
    // The newly inserted MESSAGE must be the row at id = messageEntryId.
    HarnessSessionEntryDTO message =
        entries.stream()
            .filter(e -> messageEntryId == Long.parseLong(e.getEntryId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected inserted MESSAGE not returned"));
    assertEquals(Long.toString(boot.configEntryId()), message.getParentEntryId());
    assertEquals("MESSAGE", message.getEntryType());
    assertEquals("{\"k\": 1}", message.getPayloadJson());
  }

  @Test
  void readsBranchPathDerivedStatusAndSnapshots() {
    ThreadCommandTransactions.BootstrapResult bootstrap =
        commandTransactions.bootstrapThread(
            commandTransactions.createThread(NOW).id(),
            0L,
            "session",
            TestRuntimeConfigs.bootstrap(),
            NOW);
    String sessionId = Long.toString(bootstrap.session().id());
    String threadId = Long.toString(bootstrap.thread().id());
    long executionEpoch = bootstrap.thread().executionEpoch();

    // Branch path: ROOT -> MESSAGE after advancing the Thread head.
    long assistantEntryId = bootstrap.rootEntry().id() + 10;
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{\"kind\":\"leaf\"}'::jsonb, ?)",
        assistantEntryId,
        bootstrap.session().id(),
        bootstrap.rootEntry().id(),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    long leafEntryId = bootstrap.rootEntry().id() + 20;
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{\"kind\":\"leaf2\"}'::jsonb, ?)",
        leafEntryId,
        bootstrap.session().id(),
        assistantEntryId,
        OffsetDateTime.ofInstant(NOW.plusSeconds(2), ZoneOffset.UTC));
    jdbc.update(
        "update harness_thread set head_entry_id = ? where id = ?",
        leafEntryId,
        Long.parseLong(threadId));
    HarnessThreadSnapshotDTO pathSnapshot = threadQueryService.getSnapshot(threadId);
    List<HarnessSessionEntryDTO> path = pathSnapshot.getEntries();
    assertEquals(
        List.of(bootstrap.rootEntry().id(), assistantEntryId, leafEntryId),
        path.stream().map(e -> Long.parseLong(e.getEntryId())).toList());
    assertEquals("ROOT", path.get(0).getEntryType());
    assertEquals("MESSAGE", path.get(2).getEntryType());

    HarnessSessionDTO loaded = sessionQueryService.getSession(sessionId);
    assertEquals("session", loaded.getTitle());

    // Bound Thread derives its Session from the head Entry.
    HarnessThreadDTO boundView = threadQueryService.getThread(threadId);
    assertEquals(sessionId, boundView.getSessionId());
    assertEquals("session", boundView.getSessionTitle());

    // IDLE by default
    assertEquals("IDLE", threadQueryService.getThread(threadId).getStatus());

    // RUNNABLE
    jdbc.update("update harness_thread set runnable = true where id = ?", Long.parseLong(threadId));
    assertEquals("RUNNABLE", threadQueryService.getThread(threadId).getStatus());

    // WAITING via QUEUED input (overrides runnable)
    commandTransactions.enqueue(
        Long.parseLong(threadId),
        userPayload("hello"),
        "msg-1",
        executionEpoch,
        NOW.plusSeconds(3));
    HarnessThreadDTO waiting = threadQueryService.getThread(threadId);
    assertEquals("WAITING", waiting.getStatus());
    List<HarnessThreadInputDTO> inputs = threadQueryService.getSnapshot(threadId).getInputs();
    assertEquals(1, inputs.size());
    assertEquals("USER_MESSAGE", inputs.get(0).getInputType());
    assertEquals("QUEUED", inputs.get(0).getStatus());
    assertEquals("msg-1", inputs.get(0).getClientMessageId());
    assertTrue(inputs.get(0).getPayloadJson().contains("hello"));

    // RUNNING via active processor lease (overrides waiting)；相对真实 clock 而非 fixture NOW
    jdbc.update(
        "update harness_thread set processor_token = 'lease-1',"
            + " processor_until = current_timestamp + interval '1 hour' where id = ?",
        Long.parseLong(threadId));
    HarnessThreadDTO running = threadQueryService.getThread(threadId);
    assertEquals("RUNNING", running.getStatus());
    assertTrue(Boolean.TRUE.equals(running.getProcessing()));
    assertNull(running.getActiveAgentDefinitionId());
    assertNull(running.getModelId());
    assertNull(running.getYoloEnabled());

    // model + tool + open interaction snapshot
    long modelInvocationId = Long.parseLong(threadId) + 70;
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, created_at) values (?, ?, ?, ?,"
            + " '{\"model\":\"stub\"}'::jsonb, 'QUEUED', 1, ?)",
        modelInvocationId,
        Long.parseLong(threadId),
        leafEntryId,
        executionEpoch,
        OffsetDateTime.ofInstant(NOW.plusSeconds(4), ZoneOffset.UTC));
    long interactionId = Long.parseLong(threadId) + 80;
    jdbc.update(
        "insert into harness_interaction (id, owner_kind, owner_id, handler_type, request, status,"
            + " version, created_at) values (?, 'THREAD', ?, 'ASK', '{\"q\":1}'::jsonb, 'OPEN', 0, ?)",
        interactionId,
        Long.parseLong(threadId),
        OffsetDateTime.ofInstant(NOW.plusSeconds(5), ZoneOffset.UTC));
    long toolInvocationId = Long.parseLong(threadId) + 50;
    jdbc.update(
        "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id, ordinal,"
            + " tool_call_id, descriptor, arguments, location, environment_name, execution_epoch,"
            + " status, attempt, finished_at, created_at) values (?, ?, ?, ?, 0, 'call-1',"
            + " cast(? as jsonb), '{}'::jsonb, 'PLATFORM', null, ?, 'CANCELLED', 1, ?, ?)",
        toolInvocationId,
        Long.parseLong(threadId),
        bootstrap.session().id(),
        assistantEntryId,
        TOOL_DESCRIPTOR_JSON,
        executionEpoch,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

    List<ModelInvocationDTO> models = observabilityQueryService.listModelInvocations(threadId);
    assertEquals(1, models.size());
    assertEquals(Long.toString(modelInvocationId), models.get(0).getId());
    assertEquals("QUEUED", models.get(0).getStatus());

    List<ToolInvocationDTO> tools = observabilityQueryService.listToolInvocations(threadId);
    assertEquals(1, tools.size());
    assertEquals(Long.toString(toolInvocationId), tools.get(0).getId());

    List<InteractionDTO> opens = observabilityQueryService.listOpenInteractions(threadId);
    assertEquals(1, opens.size());
    assertEquals(Long.toString(interactionId), opens.get(0).getId());
    assertEquals("OPEN", opens.get(0).getStatus());
    assertEquals("THREAD", opens.get(0).getOwnerKind());
  }

  @Test
  void projectsFrozenRuntimeConfigFromTheCurrentHeadPath() {
    TestThreads.Bootstrapped boot =
        TestThreads.bootstrap(
            commandTransactions,
            "runtime-config",
            TestRuntimeConfigs.config("frozen-agent", true),
            NOW);
    long replacementConfigEntryId = boot.configEntryId() + 1_000_000L;
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'RUNTIME_CONFIG', cast(? as jsonb), ?)",
        replacementConfigEntryId,
        boot.sessionId(),
        boot.configEntryId(),
        RUNTIME_CONFIG_CODEC.encode(TestRuntimeConfigs.config("nearest-agent", false)),
        OffsetDateTime.ofInstant(NOW.plusMillis(500), ZoneOffset.UTC));
    long messageEntryId = replacementConfigEntryId + 1;
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)",
        messageEntryId,
        boot.sessionId(),
        replacementConfigEntryId,
        OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC));
    jdbc.update(
        "update harness_thread set head_entry_id = ? where id = ?",
        messageEntryId,
        boot.threadId());

    HarnessThreadDTO projected = threadQueryService.getThread(Long.toString(boot.threadId()));

    assertEquals("1", projected.getActiveAgentDefinitionId());
    assertEquals("nearest-agent", projected.getActiveAgentName());
    assertEquals("2", projected.getModelId());
    assertEquals("default", projected.getVariant());
    assertEquals(false, projected.getYoloEnabled());
  }

  @Test
  void unknownSessionAndEntryLookupsThrow() {
    // A session id beyond the PostgreSQL bigint sequence is structurally unknown.
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionQueryService.getSession(String.valueOf(Long.MAX_VALUE)));
    // Blank / negative / non-decimal ids are rejected at the decimal boundary.
    assertThrows(IllegalArgumentException.class, () -> sessionQueryService.getSession("abc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> threadQueryService.getSnapshot(String.valueOf(Long.MAX_VALUE)));
  }

  private static RuntimeEntryInputPayload userPayload(String content) {
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.<AgentMessageContent>of(new TextMessageContent(content)))));
  }
}
