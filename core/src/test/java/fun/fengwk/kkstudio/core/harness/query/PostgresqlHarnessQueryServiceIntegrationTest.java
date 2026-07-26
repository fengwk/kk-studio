package fun.fengwk.kkstudio.core.harness.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.harness.observability.service.impl.HarnessObservabilityQueryServiceImpl;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.harness.thread.command.TestRuntimeConfigs;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

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
  void readsRootChildSessionsBranchPathDerivedStatusAndSnapshots() {
    ThreadCommandTransactions.SessionCreation root =
        commandTransactions.createSession("root-session", TestRuntimeConfigs.bootstrap(), NOW);
    String rootSessionId = Long.toString(root.session().id());
    // Session creation no longer creates a Thread; bind a reusable Thread onto the ROOT entry.
    HarnessThread created = commandTransactions.createThread(NOW);
    long rootThread = created.id();
    assertNull(created.headEntryId(), "new threads start UNBOUND");
    assertEquals("UNBOUND", threadQueryService.getThread(Long.toString(rootThread)).getStatus());
    HarnessThread bound =
        commandTransactions.updateHead(
            rootThread, created.executionEpoch(), root.rootEntry().id(), NOW);
    long rootEpoch = bound.executionEpoch();
    String rootThreadId = Long.toString(rootThread);

    // child session under parent tool invocation
    long assistantEntryId = root.rootEntry().id() + 10;
    long toolInvocationId = rootThread + 50;
    long childSessionId = root.session().id() + 100;
    long childRootEntryId = root.rootEntry().id() + 100;
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)",
        assistantEntryId,
        root.session().id(),
        root.rootEntry().id(),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    // parent tool for child session FK：用终态 CANCELLED，避免把 root thread 误判为 WAITING
    jdbc.update(
        "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id, ordinal,"
            + " tool_call_id, descriptor, arguments, location, environment_name, execution_epoch,"
            + " status, attempt, finished_at, created_at) values (?, ?, ?, ?, 0, 'call-1',"
            + " cast(? as jsonb), '{}'::jsonb, 'PLATFORM', null, ?, 'CANCELLED', 1, ?, ?)",
        toolInvocationId,
        rootThread,
        root.session().id(),
        assistantEntryId,
        TOOL_DESCRIPTOR_JSON,
        rootEpoch,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    OffsetDateTime childNow = OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC);
    // Session/Entry no longer participate in a FK cycle with Thread, so plain inserts suffice.
    jdbc.update(
        "insert into harness_session (id, title, parent_session_id, parent_invocation_id,"
            + " created_at, updated_at) values (?, 'child-session', ?, ?, ?, ?)",
        childSessionId,
        root.session().id(),
        toolInvocationId,
        childNow,
        childNow);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{}'::jsonb, ?)",
        childRootEntryId,
        childSessionId,
        childNow);
    HarnessThread childCreated = commandTransactions.createThread(NOW.plusSeconds(1));
    long childThreadId = childCreated.id();
    commandTransactions.updateHead(
        childThreadId, childCreated.executionEpoch(), childRootEntryId, NOW.plusSeconds(1));

    HarnessSessionDTO loadedRoot = sessionQueryService.getSession(rootSessionId);
    assertEquals("root-session", loadedRoot.getTitle());
    assertNull(loadedRoot.getParentSessionId());
    assertEquals(rootSessionId, loadedRoot.getRootSessionId());
    assertEquals(0, loadedRoot.getDepth());
    assertTrue(
        sessionQueryService.listRootSessions().stream()
            .anyMatch(s -> rootSessionId.equals(s.getSessionId())));

    HarnessSessionDTO loadedChild = sessionQueryService.getSession(Long.toString(childSessionId));
    assertEquals(rootSessionId, loadedChild.getParentSessionId());
    assertEquals(Long.toString(toolInvocationId), loadedChild.getParentInvocationId());
    assertNull(loadedChild.getRootSessionId());
    assertNull(loadedChild.getDepth());

    // Bound Thread derives its Session from the head Entry.
    HarnessThreadDTO boundView = threadQueryService.getThread(rootThreadId);
    assertEquals(rootSessionId, boundView.getSessionId());
    assertEquals("root-session", boundView.getSessionTitle());

    // branch path: ROOT -> MESSAGE on root thread after advancing head
    long leafEntryId = root.rootEntry().id() + 20;
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{\"kind\":\"leaf\"}'::jsonb, ?)",
        leafEntryId,
        root.session().id(),
        assistantEntryId,
        OffsetDateTime.ofInstant(NOW.plusSeconds(2), ZoneOffset.UTC));
    jdbc.update(
        "update harness_thread set head_entry_id = ? where id = ?", leafEntryId, rootThread);
    List<HarnessSessionEntryDTO> path = threadQueryService.listPathEntries(rootThreadId);
    assertEquals(
        List.of(root.rootEntry().id(), assistantEntryId, leafEntryId),
        path.stream().map(e -> Long.parseLong(e.getEntryId())).toList());
    assertEquals("ROOT", path.get(0).getEntryType());
    assertEquals("MESSAGE", path.get(2).getEntryType());

    // IDLE by default
    assertEquals("IDLE", threadQueryService.getThread(rootThreadId).getStatus());

    // RUNNABLE
    jdbc.update("update harness_thread set runnable = true where id = ?", rootThread);
    assertEquals("RUNNABLE", threadQueryService.getThread(rootThreadId).getStatus());

    // WAITING via QUEUED input (overrides runnable)
    commandTransactions.enqueue(
        rootThread, userPayload("hello"), "msg-1", rootEpoch, NOW.plusSeconds(3));
    HarnessThreadDTO waiting = threadQueryService.getThread(rootThreadId);
    assertEquals("WAITING", waiting.getStatus());
    List<HarnessThreadInputDTO> inputs = threadQueryService.listInputs(rootThreadId);
    assertEquals(1, inputs.size());
    assertEquals("USER_MESSAGE", inputs.get(0).getInputType());
    assertEquals("QUEUED", inputs.get(0).getStatus());
    assertEquals("msg-1", inputs.get(0).getClientMessageId());
    assertTrue(inputs.get(0).getPayloadJson().contains("hello"));

    // RUNNING via active processor lease (overrides waiting)；相对真实 clock 而非 fixture NOW
    jdbc.update(
        "update harness_thread set processor_token = 'lease-1',"
            + " processor_until = current_timestamp + interval '1 hour' where id = ?",
        rootThread);
    HarnessThreadDTO running = threadQueryService.getThread(rootThreadId);
    assertEquals("RUNNING", running.getStatus());
    assertTrue(Boolean.TRUE.equals(running.getProcessing()));
    assertNull(running.getActiveAgentDefinitionId());
    assertNull(running.getModelId());
    assertNull(running.getYoloEnabled());

    // model + tool + open interaction snapshot
    long modelInvocationId = rootThread + 70;
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, session_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, created_at) values (?, ?, ?, ?, ?,"
            + " '{\"model\":\"stub\"}'::jsonb, 'QUEUED', 1, ?)",
        modelInvocationId,
        rootThread,
        root.session().id(),
        leafEntryId,
        rootEpoch,
        OffsetDateTime.ofInstant(NOW.plusSeconds(4), ZoneOffset.UTC));
    long interactionId = rootThread + 80;
    jdbc.update(
        "insert into harness_interaction (id, owner_kind, owner_id, handler_type, request, status,"
            + " version, created_at) values (?, 'THREAD', ?, 'ASK', '{\"q\":1}'::jsonb, 'OPEN', 0, ?)",
        interactionId,
        rootThread,
        OffsetDateTime.ofInstant(NOW.plusSeconds(5), ZoneOffset.UTC));

    List<ModelInvocationDTO> models = observabilityQueryService.listModelInvocations(rootThreadId);
    assertEquals(1, models.size());
    assertEquals(Long.toString(modelInvocationId), models.get(0).getId());
    assertEquals("QUEUED", models.get(0).getStatus());

    List<ToolInvocationDTO> tools = observabilityQueryService.listToolInvocations(rootThreadId);
    assertEquals(1, tools.size());
    assertEquals(Long.toString(toolInvocationId), tools.get(0).getId());

    List<InteractionDTO> opens = observabilityQueryService.listOpenInteractions(rootThreadId);
    assertEquals(1, opens.size());
    assertEquals(Long.toString(interactionId), opens.get(0).getId());
    assertEquals("OPEN", opens.get(0).getStatus());
    assertEquals("THREAD", opens.get(0).getOwnerKind());

    // Root activities: session tree + derived status projection
    List<RootActivityDTO> activities =
        observabilityQueryService.listRootActivities(rootSessionId, 0, 50);
    assertFalse(activities.isEmpty());
    assertTrue(activities.stream().anyMatch(a -> rootThreadId.equals(a.getThreadId())));
    assertTrue(
        activities.stream().anyMatch(a -> Long.toString(childThreadId).equals(a.getThreadId())));
    assertNotNull(activities.get(0).getEventType());
    assertNotNull(activities.get(0).getPayloadJson());

    // child remains IDLE
    assertEquals("IDLE", threadQueryService.getThread(Long.toString(childThreadId)).getStatus());
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
