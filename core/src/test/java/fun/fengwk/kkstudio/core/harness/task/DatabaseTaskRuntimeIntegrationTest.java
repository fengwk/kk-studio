package fun.fengwk.kkstudio.core.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.task.service.DatabaseTaskRuntime;
import fun.fengwk.kkstudio.core.harness.task.store.DatabaseRootActivityStore;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.task.TaskCommand;
import fun.fengwk.kkstudio.harness.runtime.task.TaskInspection;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.task.WorkspacePolicy;
import fun.fengwk.kkstudio.harness.runtime.task.WorkspaceRevisionResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2 durability coverage for the task aggregate and its root activity projection. */
@SpringBootTest(
    classes = {
      CoreTestApplication.class,
      DatabaseTaskRuntimeIntegrationTest.TaskTestConfiguration.class
    })
class DatabaseTaskRuntimeIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final SessionEntryJsonCodec ENTRY_CODEC = new SessionEntryJsonCodec();

  @Autowired private DatabaseTaskRuntime runtime;
  @Autowired private DatabaseRootActivityStore rootActivityStore;
  @Autowired private SnowflakeRunIdGenerator ids;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute("alter table harness_run_event drop constraint if exists unique_event_type");
    jdbc.update("delete from tool_artifact");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
    jdbc.update("delete from agent_definition");
    jdbc.update("delete from workspace");
  }

  /**
   * A first invocation atomically stores every row required to recover the queued child attempt.
   */
  @Test
  void createsAtomicChildSnapshotPromptRunRelationAndEvents() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));

    TaskInspection inspection = runtime.startOrResume(fixture.context(), command(null), NOW);

    assertEquals(TaskState.RUNNING, inspection.task().state());
    assertEquals(1, count("harness_subagent_task"));
    assertEquals(2, count("harness_session"));
    assertEquals(3, count("harness_session_entry"));
    assertEquals(2, count("harness_run"));
    assertEquals(
        "QUEUED",
        value("select status from harness_run where id = ?", inspection.task().childRunId()));
    assertEquals(
        fixture.parentSessionId,
        longValue(
            "select parent_session_id from harness_session where id = ?",
            inspection.task().childSessionId()));
    assertEquals(
        "agent_snapshot",
        value(
            "select entry_type from harness_session_entry where session_id = ? order by id limit 1",
            inspection.task().childSessionId()));
    assertEquals(
        "message",
        value(
            "select entry_type from harness_session_entry where session_id = ? order by id desc"
                + " limit 1",
            inspection.task().childSessionId()));
    assertEquals(
        "rev-" + inspection.task().childSessionId(), inspection.task().workspaceRevision());
    assertEquals(2, count("harness_run_event"));
    assertEquals(
        List.of("subagent_started"),
        jdbc.queryForList(
            "select event_type from harness_run_event where run_id = ? order by sequence",
            String.class,
            fixture.parentRunId));
  }

  /**
   * Reclaiming the same running invocation returns the durable relation without duplicate side
   * effects.
   */
  @Test
  void replaysSameInvocationWithoutDuplicateRows() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection first = runtime.startOrResume(fixture.context(), command(null), NOW);
    TaskInspection replay =
        runtime.startOrResume(fixture.context(), command(null), NOW.plusSeconds(1));

    assertEquals(first.task().childSessionId(), replay.task().childSessionId());
    assertEquals(first.task().childRunId(), replay.task().childRunId());
    assertEquals(1, count("harness_subagent_task"));
    assertEquals(2, count("harness_session"));
    assertEquals(2, count("harness_run"));
    assertEquals(2, count("harness_run_event"));
  }

  /** Authorization and target lookup occur before durable child state is created. */
  @Test
  void rejectsUnauthorizedAndUnknownTargetsWithoutResidue() {
    Fixture denied = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.startOrResume(
                denied.context(),
                new TaskCommand("Other", "work", null, WorkspacePolicy.FORK),
                NOW));
    assertChildStateAbsent();

    jdbc.update("delete from agent_definition where workspace_id = ? and name = ?", 1L, "Child");
    long unknownInvocation = denied.newInvocation();
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.startOrResume(denied.context(unknownInvocation), command(null), NOW));
    assertChildStateAbsent();
  }

  /** Parent snapshot limits govern depth and active direct/root child attempts. */
  @Test
  void enforcesDepthDirectAndRootActiveLimits() {
    Fixture depth = fixture(policy(1, 2, 2, 4, null), policy(3, 3, 5, 7, null));
    jdbc.update("update harness_session set depth = 1 where id = ?", depth.parentSessionId);
    assertThrows(
        IllegalStateException.class,
        () -> runtime.startOrResume(depth.context(), command(null), NOW));

    clean();
    Fixture direct = fixture(policy(3, 1, 3, 4, null), policy(3, 3, 5, 7, null));
    runtime.startOrResume(direct.context(), command(null), NOW);
    long secondInvocation = direct.newInvocation();
    assertThrows(
        IllegalStateException.class,
        () -> runtime.startOrResume(direct.context(secondInvocation), command(null), NOW));

    clean();
    Fixture root = fixture(policy(3, 3, 1, 4, null), policy(3, 3, 5, 7, null));
    runtime.startOrResume(root.context(), command(null), NOW);
    long siblingParent = root.newParent(0, root.rootSessionId);
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.startOrResume(
                root.context(newInvocation(siblingParent), siblingParent), command(null), NOW));
  }

  /**
   * Resume appends to the frozen child path; the active-child and racing-resume paths leave no
   * extra run.
   */
  @Test
  void resumesOnlyInactiveChildAndConcurrentResumeHasOneWinner() throws Exception {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection created = runtime.startOrResume(fixture.context(), command(null), NOW);
    terminateChild(created, "SUCCEEDED", "first");
    runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));

    long resumeInvocation = fixture.newInvocation();
    TaskInspection resumed =
        runtime.startOrResume(
            fixture.context(resumeInvocation),
            command(created.task().childSessionId()),
            NOW.plusSeconds(2));
    assertEquals(created.task().childSessionId(), resumed.task().childSessionId());
    assertEquals(2, countWhere("harness_run", "session_id = ?", created.task().childSessionId()));
    assertEquals(
        "subagent_resumed",
        value(
            "select event_type from harness_run_event where run_id = ? order by sequence desc limit"
                + " 1",
            fixture.parentRunId));
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.startOrResume(
                fixture.context(fixture.newInvocation()),
                command(created.task().childSessionId()),
                NOW.plusSeconds(3)));

    terminateChild(resumed, "SUCCEEDED", "second");
    runtime.inspect(resumeInvocation, NOW.plusSeconds(4));
    long left = fixture.newInvocation();
    long right = fixture.newInvocation();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (long invocation : List.of(left, right)) {
      futures.add(
          executor.submit(
              () ->
                  runResume(
                      start, fixture, invocation, created.task().childSessionId(), successes)));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdownNow();
    assertEquals(1, successes.get());
    assertEquals(3, countWhere("harness_run", "session_id = ?", created.task().childSessionId()));
    assertEquals(
        3,
        countWhere(
            "harness_subagent_task", "child_session_id = ?", created.task().childSessionId()));
  }

  /**
   * Resuming is based only on the frozen child relation and snapshot, not its mutable definition.
   */
  @Test
  void resumesAfterTargetDefinitionIsDeleted() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection created = runtime.startOrResume(fixture.context(), command(null), NOW);
    terminateChild(created, "SUCCEEDED", "first");
    runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));
    jdbc.update("delete from agent_definition where id = ?", fixture.childAgentId);

    long resumeInvocation = fixture.newInvocation();
    TaskInspection resumed =
        runtime.startOrResume(
            fixture.context(resumeInvocation),
            command(created.task().childSessionId()),
            NOW.plusSeconds(2));

    assertEquals(created.task().targetAgent(), resumed.task().targetAgent());
    assertEquals(created.task().workspacePolicy(), resumed.task().workspacePolicy());
    assertEquals(created.task().workspaceRevision(), resumed.task().workspaceRevision());
  }

  /** A resume cannot combine the creation revision with a different effective workspace policy. */
  @Test
  void rejectsResumeWithWorkspacePolicyDifferentFromCreation() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection created = runtime.startOrResume(fixture.context(), command(null), NOW);
    terminateChild(created, "SUCCEEDED", "first");
    runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.startOrResume(
                fixture.context(fixture.newInvocation()),
                new TaskCommand(
                    "Child", "complete it", created.task().childSessionId(), WorkspacePolicy.NONE),
                NOW.plusSeconds(2)));
    assertEquals(1, countWhere("harness_run", "session_id = ?", created.task().childSessionId()));
    assertEquals(
        1,
        countWhere(
            "harness_subagent_task", "child_session_id = ?", created.task().childSessionId()));
  }

  /**
   * Snapshot lookup follows the current leaf, and target policy remains frozen after definition
   * mutation.
   */
  @Test
  void usesNearestCurrentPathSnapshotAndFrozenChildPolicy() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 1, null));
    long currentSnapshot = 1L;
    jdbc.update(
        "update harness_session_entry set payload_json = ? where id = ?",
        ENTRY_CODEC.encode(snapshot(policy(3, 3, 5, 4, null), List.of("Other"))),
        fixture.parentSnapshotId);
    insertEntry(
        currentSnapshot,
        fixture.parentSessionId,
        fixture.parentSnapshotId,
        null,
        "agent_snapshot",
        snapshot(policy(3, 3, 5, 4, null), List.of("Child")));
    jdbc.update(
        "update harness_session set leaf_entry_id = ? where id = ?",
        currentSnapshot,
        fixture.parentSessionId);
    TaskInspection task = runtime.startOrResume(fixture.context(), command(null), NOW);
    assertEquals(1, task.task().maxTurns());
    jdbc.update(
        "update agent_definition set config_json = ? where id = ?",
        config(policy(3, 3, 5, 99, null), List.of()),
        fixture.childAgentId);
    jdbc.update("update harness_run set turn_index = 1 where id = ?", task.task().childRunId());
    runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));
    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            task.task().childRunId()));
  }

  /**
   * Terminal reports project ordered/deduplicated artifacts, joined assistant text, and frozen
   * revision.
   */
  @Test
  void completesSucceededFailedAndCancelledWithDurableReports() {
    for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      clean();
      Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
      TaskInspection task = runtime.startOrResume(fixture.context(), command(null), NOW);
      appendArtifactInvocation(task.task().childRunId(), 0, "a", "b");
      appendArtifactInvocation(task.task().childRunId(), 1, "b", "c");
      terminateChild(task, terminal, "one", "two");

      TaskInspection completed = runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));
      assertEquals(TaskState.valueOf(terminal), completed.task().state());
      assertEquals("one\ntwo", completed.report().finalAssistantReport());
      assertEquals(
          List.of("a", "b", "c"),
          completed.report().artifacts().stream().map(ArtifactRef::artifactId).toList());
      assertEquals("rev-" + task.task().childSessionId(), completed.report().workspaceRevision());
      assertEquals(
          terminal,
          value(
              "select status from harness_subagent_task where parent_invocation_id = ?",
              fixture.invocationId));
      assertEquals(
          1,
          countWhere(
              "harness_run_event",
              "run_id = ? and event_type = 'subagent_completed'",
              fixture.parentRunId));
    }
  }

  /** A resumed report projects only assistant messages written by its own child run. */
  @Test
  void resumedReportExcludesAssistantTextFromPreviousChildRun() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection first = runtime.startOrResume(fixture.context(), command(null), NOW);
    terminateChild(first, "SUCCEEDED", "first-run");
    runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));

    long resumeInvocation = fixture.newInvocation();
    TaskInspection resumed =
        runtime.startOrResume(
            fixture.context(resumeInvocation),
            command(first.task().childSessionId()),
            NOW.plusSeconds(2));
    terminateChild(resumed, "SUCCEEDED", "resumed-run");

    TaskInspection completed = runtime.inspect(resumeInvocation, NOW.plusSeconds(3));
    assertEquals("resumed-run", completed.report().finalAssistantReport());
  }

  /**
   * Max-turn and semantic idle time request cancellation once; a heartbeat timestamp is irrelevant.
   */
  @Test
  void requestsMaxTurnAndIdleCancellationOnceFromSemanticActivity() {
    Fixture turns = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 1, null));
    TaskInspection turnTask = runtime.startOrResume(turns.context(), command(null), NOW);
    jdbc.update("update harness_run set turn_index = 1 where id = ?", turnTask.task().childRunId());
    runtime.inspect(turns.invocationId, NOW.plusSeconds(1));
    runtime.inspect(turns.invocationId, NOW.plusSeconds(2));
    assertEquals(
        1,
        countWhere(
            "harness_run_event",
            "run_id = ? and event_type = 'subagent_cancel_requested'",
            turns.parentRunId));

    clean();
    Fixture idle = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, 100L));
    TaskInspection idleTask = runtime.startOrResume(idle.context(), command(null), NOW);
    jdbc.update(
        "update harness_run set gmt_modified = ? where id = ?",
        timestamp(NOW.plusSeconds(10)),
        idleTask.task().childRunId());
    runtime.inspect(idle.invocationId, NOW.plusMillis(100));
    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            idleTask.task().childRunId()));
    assertTrue(
        value(
                "select payload_json from harness_run_event where run_id = ? and event_type ="
                    + " 'subagent_cancel_requested'",
                idle.parentRunId)
            .contains("idle_timeout"));
  }

  /**
   * Recursive cancellation reaches active descendants exactly once without deleting durable
   * sessions.
   */
  @Test
  void recursivelyCancelsNestedRunsAndPreservesSessions() {
    Fixture fixture = fixture(policy(4, 3, 5, 4, null), policy(4, 3, 5, 7, null));
    TaskInspection child = runtime.startOrResume(fixture.context(), command(null), NOW);
    jdbc.update(
        "update harness_run set status = 'WAITING_TOOLS' where id = ?", child.task().childRunId());
    long nestedInvocation = fixture.newInvocation(child.task().childRunId());
    TaskInspection grandchild =
        runtime.startOrResume(
            fixture.context(nestedInvocation, child.task().childRunId()),
            command(null),
            NOW.plusSeconds(1));

    runtime.cancelTree(fixture.invocationId, NOW.plusSeconds(2));
    runtime.cancelTree(fixture.invocationId, NOW.plusSeconds(3));
    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            child.task().childRunId()));
    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            grandchild.task().childRunId()));
    assertEquals(3, count("harness_session"));
    assertEquals(
        1,
        countWhere(
            "harness_run_event",
            "run_id = ? and event_type = 'subagent_cancel_requested'",
            fixture.parentRunId));
  }

  /** A tree cancellation follows each descendant session's current active run after resumes. */
  @Test
  void cancelsResumedNestedDescendantInsteadOfHistoricalTaskRun() {
    Fixture fixture = fixture(policy(4, 3, 5, 4, null), policy(4, 3, 5, 7, null));
    TaskInspection firstChild = runtime.startOrResume(fixture.context(), command(null), NOW);
    jdbc.update(
        "update harness_run set status = 'WAITING_TOOLS' where id = ?",
        firstChild.task().childRunId());
    long firstGrandchildInvocation = fixture.newInvocation(firstChild.task().childRunId());
    TaskInspection firstGrandchild =
        runtime.startOrResume(
            fixture.context(firstGrandchildInvocation, firstChild.task().childRunId()),
            command(null),
            NOW.plusSeconds(1));
    terminateChild(firstGrandchild, "SUCCEEDED", "first-grandchild");
    runtime.inspect(firstGrandchildInvocation, NOW.plusSeconds(2));
    terminateChild(firstChild, "SUCCEEDED", "first-child");
    runtime.inspect(fixture.invocationId, NOW.plusSeconds(3));

    long childResumeInvocation = fixture.newInvocation();
    TaskInspection resumedChild =
        runtime.startOrResume(
            fixture.context(childResumeInvocation),
            command(firstChild.task().childSessionId()),
            NOW.plusSeconds(4));
    jdbc.update(
        "update harness_run set status = 'WAITING_TOOLS' where id = ?",
        resumedChild.task().childRunId());
    long grandchildResumeInvocation = fixture.newInvocation(resumedChild.task().childRunId());
    TaskInspection resumedGrandchild =
        runtime.startOrResume(
            fixture.context(grandchildResumeInvocation, resumedChild.task().childRunId()),
            command(firstGrandchild.task().childSessionId()),
            NOW.plusSeconds(5));

    runtime.cancelTree(fixture.invocationId, NOW.plusSeconds(6));

    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            resumedChild.task().childRunId()));
    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            resumedGrandchild.task().childRunId()));
  }

  /**
   * Root projection includes descendant permission events and enforces event cursor, workspace and
   * root boundaries.
   */
  @Test
  void projectsRootActivityByEventIdAcrossDescendantsOnly() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection child = runtime.startOrResume(fixture.context(), command(null), NOW);
    insertEvent(
        child.task().childRunId(),
        2,
        RunEventType.PERMISSION_REQUESTED.value(),
        "{\"permission\":true}",
        NOW.plusSeconds(1));
    Fixture other = fixtureInWorkspace(2L, 2L);
    TaskInspection otherChild = runtime.startOrResume(other.context(), command(null), NOW);
    List<RootActivity> activity = rootActivityStore.list(1, fixture.rootSessionId, 0, 20);
    assertTrue(
        activity.stream()
            .anyMatch(
                event ->
                    event.runId() == child.task().childRunId()
                        && event.type() == RunEventType.PERMISSION_REQUESTED));
    long cursor = activity.get(0).eventId();
    assertTrue(
        rootActivityStore.list(1, fixture.rootSessionId, cursor, 20).stream()
            .allMatch(event -> event.eventId() > cursor));
    assertTrue(
        rootActivityStore.list(1, fixture.rootSessionId, 0, 20).stream()
            .noneMatch(event -> event.runId() == otherChild.task().childRunId()));
    assertTrue(
        rootActivityStore.list(2, other.rootSessionId, 0, 20).stream()
            .allMatch(event -> event.workspaceId() == 2));
  }

  /**
   * A late event insert failure is transactional: no child/task state remains after the failed
   * start.
   */
  @Test
  void rollsBackAllStateWhenLateEventInsertFails() {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    jdbc.update(
        "alter table harness_run_event add constraint unique_event_type unique (event_type)");
    insertEvent(
        fixture.parentRunId, 1, RunEventType.SUBAGENT_STARTED.value(), "{}", NOW.minusSeconds(1));

    assertThrows(
        RuntimeException.class, () -> runtime.startOrResume(fixture.context(), command(null), NOW));
    assertEquals(0, count("harness_subagent_task"));
    assertEquals(1, count("harness_session"));
    assertEquals(1, count("harness_session_entry"));
    assertEquals(1, count("harness_run"));
    assertEquals(1, count("harness_run_event"));
  }

  /**
   * Concurrent replay, inspect, and cancel complete deterministically without lock cycles or
   * duplicate work.
   */
  @Test
  void concurrentlyReplaysInspectsAndCancelsWithoutDeadlock() throws Exception {
    Fixture fixture = fixture(policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
    TaskInspection task = runtime.startOrResume(fixture.context(), command(null), NOW);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    CountDownLatch start = new CountDownLatch(1);
    Future<?> replay =
        executor.submit(
            () -> {
              await(start);
              runtime.startOrResume(fixture.context(), command(null), NOW.plusSeconds(1));
            });
    Future<?> inspect =
        executor.submit(
            () -> {
              await(start);
              runtime.inspect(fixture.invocationId, NOW.plusSeconds(1));
            });
    Future<?> cancel =
        executor.submit(
            () -> {
              await(start);
              runtime.cancelTree(fixture.invocationId, NOW.plusSeconds(1));
            });
    start.countDown();
    replay.get(10, TimeUnit.SECONDS);
    inspect.get(10, TimeUnit.SECONDS);
    cancel.get(10, TimeUnit.SECONDS);
    executor.shutdownNow();
    assertEquals(1, count("harness_subagent_task"));
    assertEquals(2, count("harness_session"));
    assertNotNull(
        jdbc.queryForObject(
            "select cancel_requested_at from harness_run where id = ?",
            Timestamp.class,
            task.task().childRunId()));
  }

  private void runResume(
      CountDownLatch start,
      Fixture fixture,
      long invocation,
      long childSessionId,
      AtomicInteger successes) {
    await(start);
    try {
      runtime.startOrResume(
          fixture.context(invocation), command(childSessionId), NOW.plusSeconds(5));
      successes.incrementAndGet();
    } catch (IllegalStateException expected) {
      // The losing transaction must roll back its prompt, run, task relation, and event.
    }
  }

  private void assertChildStateAbsent() {
    assertEquals(0, count("harness_subagent_task"));
    assertEquals(1, count("harness_session"));
    assertEquals(1, count("harness_session_entry"));
    assertEquals(1, count("harness_run"));
    assertEquals(0, count("harness_run_event"));
  }

  private Fixture fixture(String parentPolicy, String childPolicy) {
    return fixture(1L, 1L, parentPolicy, childPolicy);
  }

  private Fixture fixtureInWorkspace(long workspaceId, long rootId) {
    return fixture(workspaceId, rootId, policy(3, 3, 5, 4, null), policy(3, 3, 5, 7, null));
  }

  private Fixture fixture(long workspaceId, long rootId, String parentPolicy, String childPolicy) {
    long parentAgentId = ids.newRunId();
    long childAgentId = ids.newRunId();
    jdbc.update(
        "insert into workspace (id, name, settings_json, gmt_create, gmt_modified, version) values"
            + " (?, ?, '{}', ?, ?, 0)",
        workspaceId,
        "workspace-" + workspaceId,
        timestamp(NOW),
        timestamp(NOW));
    insertAgent(parentAgentId, workspaceId, "Parent", config(parentPolicy, List.of("Child")));
    insertAgent(childAgentId, workspaceId, "Child", config(childPolicy, List.of("Child")));
    long parentSessionId = rootId;
    long snapshotId = ids.newSessionEntryId();
    long parentRunId = ids.newRunId();
    insertSession(
        parentSessionId,
        workspaceId,
        parentAgentId,
        null,
        rootId,
        null,
        0,
        snapshotId,
        parentRunId);
    insertEntry(
        snapshotId,
        parentSessionId,
        null,
        null,
        "agent_snapshot",
        snapshot(parentPolicy, List.of("Child")));
    insertRun(parentRunId, parentSessionId, snapshotId, "WAITING_TOOLS", 0);
    long invocationId = newInvocation(parentRunId);
    return new Fixture(
        rootId, childAgentId, parentSessionId, snapshotId, parentRunId, invocationId);
  }

  private void insertAgent(long id, long workspaceId, String name, String config) {
    jdbc.update(
        "insert into agent_definition (id, workspace_id, name, description, system_prompt,"
            + " model_id, variant, config_json, gmt_create, gmt_modified, version) values (?, ?, ?,"
            + " null, 'system', 1, 'default', ?, ?, ?, 0)",
        id,
        workspaceId,
        name,
        config,
        timestamp(NOW),
        timestamp(NOW));
  }

  private long newInvocation(long runId) {
    long id = ids.newRunId();
    long assistantEntryId =
        longValue("select trigger_entry_id from harness_run where id = ?", runId);
    int ordinal =
        jdbc.queryForObject(
            "select coalesce(max(ordinal), -1) + 1 from tool_invocation where assistant_entry_id ="
                + " ?",
            Integer.class,
            assistantEntryId);
    jdbc.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id,"
            + " tool_name, tool_version, target_type, environment_id, arguments_json, status,"
            + " permission_action, permission_decision, deadline_at, lease_owner, lease_until,"
            + " cancel_requested_at, result_json, error_message, gmt_create, started_at,"
            + " finished_at, gmt_modified) values (?, ?, ?, ?, ?, 'task', '1', 'CONTROL', null,"
            + " '{}', 'RUNNING', 'NONE', null, ?, null, null, null, null, null, ?, null, null, ?)",
        id,
        runId,
        assistantEntryId,
        ordinal,
        "call-" + id,
        timestamp(NOW.plusSeconds(60)),
        timestamp(NOW),
        timestamp(NOW));
    return id;
  }

  private long newParent(int depth, long rootSessionId) {
    long sessionId = ids.newSessionEntryId();
    long snapshotId = ids.newSessionEntryId();
    long runId = ids.newRunId();
    long agentId =
        longValue("select agent_definition_id from harness_session where id = ?", rootSessionId);
    insertSession(
        sessionId, 1L, agentId, rootSessionId, rootSessionId, null, depth, snapshotId, runId);
    insertEntry(
        snapshotId,
        sessionId,
        null,
        null,
        "agent_snapshot",
        snapshot(policy(3, 3, 1, 4, null), List.of("Child")));
    insertRun(runId, sessionId, snapshotId, "WAITING_TOOLS", 0);
    return runId;
  }

  private void insertSession(
      long id,
      long workspaceId,
      long agentId,
      Long parentId,
      long rootId,
      Long parentInvocationId,
      int depth,
      long leafId,
      long activeRunId) {
    jdbc.update(
        "insert into harness_session (id, workspace_id, agent_definition_id, title, leaf_entry_id,"
            + " active_run_id, parent_session_id, root_session_id, parent_invocation_id, depth,"
            + " yolo_enabled, gmt_create, gmt_modified, version) values (?, ?, ?, null, ?, ?, ?, ?,"
            + " ?, ?, false, ?, ?, 0)",
        id,
        workspaceId,
        agentId,
        leafId,
        activeRunId,
        parentId,
        rootId,
        parentInvocationId,
        depth,
        timestamp(NOW),
        timestamp(NOW));
  }

  private void insertRun(long id, long sessionId, long triggerId, String status, int turns) {
    jdbc.update(
        "insert into harness_run (id, session_id, trigger_entry_id, status, turn_index, attempt,"
            + " event_sequence, lease_owner, lease_until, next_attempt_at, cancel_requested_at,"
            + " gmt_create, started_at, finished_at, gmt_modified) values (?, ?, ?, ?, ?, 1, 0,"
            + " null, null, ?, null, ?, null, null, ?)",
        id,
        sessionId,
        triggerId,
        status,
        turns,
        timestamp(NOW),
        timestamp(NOW),
        timestamp(NOW));
  }

  private void insertEntry(
      long id, long sessionId, Long parentId, Long runId, String type, Object payload) {
    String json =
        payload instanceof AgentSnapshotEntryPayload snapshot
            ? ENTRY_CODEC.encode(snapshot)
            : ENTRY_CODEC.encode((MessageEntryPayload) payload);
    jdbc.update(
        "insert into harness_session_entry (id, session_id, parent_entry_id, run_id, entry_type,"
            + " payload_json, gmt_create) values (?, ?, ?, ?, ?, ?, ?)",
        id,
        sessionId,
        parentId,
        runId,
        type,
        json,
        timestamp(NOW));
  }

  private AgentSnapshotEntryPayload snapshot(String policy, List<String> allowed) {
    return new AgentSnapshotEntryPayload(
        new AgentSnapshot("system", "1", "default", List.of(), List.of(), allowed, policy));
  }

  private void terminateChild(TaskInspection task, String status, String... messages) {
    long parent =
        longValue(
            "select leaf_entry_id from harness_session where id = ?", task.task().childSessionId());
    long leaf = parent;
    for (String message : messages) {
      leaf = ids.newSessionEntryId();
      MessageEntryPayload payload =
          new MessageEntryPayload(
              new AgentMessage(
                  AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(message))),
              new AssistantMessageMetadata(
                  ProviderStopReason.COMPLETED,
                  new ModelUsage(1, 1, 1, 0, 0),
                  new ModelCost("USD", BigDecimal.ZERO)));
      insertEntry(
          leaf, task.task().childSessionId(), parent, task.task().childRunId(), "message", payload);
      parent = leaf;
    }
    jdbc.update(
        "update harness_session set leaf_entry_id = ?, active_run_id = null where id = ?",
        leaf,
        task.task().childSessionId());
    jdbc.update(
        "update harness_run set status = ?, turn_index = 2 where id = ?",
        status,
        task.task().childRunId());
  }

  private void appendArtifactInvocation(long runId, int ordinal, String... artifactIds) {
    List<ArtifactToolContent> contents = new ArrayList<>();
    for (String artifactId : artifactIds) {
      contents.add(
          new ArtifactToolContent(new ArtifactRef(artifactId, "text/plain", artifactId.length())));
    }
    long id = ids.newRunId();
    String result =
        ToolResultJsonCodec.encode(
            new ToolResult("tool-" + id, List.copyOf(contents), false, "{}", false));
    jdbc.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id,"
            + " tool_name, tool_version, target_type, environment_id, arguments_json, status,"
            + " permission_action, permission_decision, deadline_at, lease_owner, lease_until,"
            + " cancel_requested_at, result_json, error_message, gmt_create, started_at,"
            + " finished_at, gmt_modified) values (?, ?, ?, ?, ?, 'read', '1', 'CLOUD', null, '{}',"
            + " 'SUCCEEDED', 'NONE', null, ?, null, null, null, ?, null, ?, ?, ?, ?)",
        id,
        runId,
        longValue("select trigger_entry_id from harness_run where id = ?", runId),
        ordinal,
        "tool-" + id,
        timestamp(NOW.plusSeconds(60)),
        result,
        timestamp(NOW),
        timestamp(NOW),
        timestamp(NOW),
        timestamp(NOW));
  }

  private void insertEvent(long runId, long sequence, String type, String payload, Instant time) {
    jdbc.update(
        "update harness_run set event_sequence = greatest(event_sequence, ?) where id = ?",
        sequence,
        runId);
    jdbc.update(
        "insert into harness_run_event (id, run_id, sequence, event_type, payload_json, gmt_create)"
            + " values (?, ?, ?, ?, ?, ?)",
        ids.newRunEventId(),
        runId,
        sequence,
        type,
        payload,
        timestamp(time));
  }

  private TaskCommand command(Long sessionId) {
    return new TaskCommand("Child", "complete it", sessionId, WorkspacePolicy.FORK);
  }

  private static String policy(int depth, int direct, Integer total, int turns, Long idleMillis) {
    return "{\"maxDepth\":"
        + depth
        + ",\"maxDirectSubagents\":"
        + direct
        + ",\"maxTotalSubagents\":"
        + total
        + ",\"maxTurns\":"
        + turns
        + ",\"idleTimeoutMillis\":"
        + (idleMillis == null ? "null" : idleMillis)
        + "}";
  }

  private static String config(String policy, List<String> allowed) {
    return "{\"tools\":[],\"skills\":[],\"allowedSubagents\":["
        + allowed.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(","))
        + "],\"executionPolicy\":"
        + policy
        + "}";
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private int countWhere(String table, String where, Object... args) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + where, Integer.class, args);
  }

  private String value(String sql, Object... args) {
    return jdbc.queryForObject(sql, String.class, args);
  }

  private long longValue(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private final class Fixture {
    private final long rootSessionId;
    private final long childAgentId;
    private final long parentSessionId;
    private final long parentSnapshotId;
    private final long parentRunId;
    private final long invocationId;

    private Fixture(
        long rootSessionId,
        long childAgentId,
        long parentSessionId,
        long parentSnapshotId,
        long parentRunId,
        long invocationId) {
      this.rootSessionId = rootSessionId;
      this.childAgentId = childAgentId;
      this.parentSessionId = parentSessionId;
      this.parentSnapshotId = parentSnapshotId;
      this.parentRunId = parentRunId;
      this.invocationId = invocationId;
    }

    private ToolExecutionContext context() {
      return context(invocationId, parentRunId);
    }

    private ToolExecutionContext context(long invocation) {
      return context(invocation, parentRunId);
    }

    private ToolExecutionContext context(long invocation, long runId) {
      return new ToolExecutionContext(invocation, runId);
    }

    private long newInvocation() {
      return DatabaseTaskRuntimeIntegrationTest.this.newInvocation(parentRunId);
    }

    private long newInvocation(long runId) {
      return DatabaseTaskRuntimeIntegrationTest.this.newInvocation(runId);
    }

    private long newParent(int depth, long rootId) {
      return DatabaseTaskRuntimeIntegrationTest.this.newParent(depth, rootId);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TaskTestConfiguration {
    @Bean
    @Primary
    WorkspaceRevisionResolver testWorkspaceRevisionResolver() {
      return (workspaceId, policy, childSessionId) -> Optional.of("rev-" + childSessionId);
    }
  }
}
