package fun.fengwk.kkstudio.harness.runtime.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Unit coverage for create/get/update goal PLATFORM tools and schemas. */
class GoalToolsTest {
  private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void createGetUpdateLifecycle() {
    InMemoryGoalStore store = new InMemoryGoalStore();
    CreateGoalTool create = new CreateGoalTool(store, CLOCK);
    GetGoalTool get = new GetGoalTool(store);
    UpdateGoalTool update = new UpdateGoalTool(store, CLOCK);

    assertEquals("create_goal", create.descriptor().name());
    assertEquals("get_goal", get.descriptor().name());
    assertEquals("update_goal", update.descriptor().name());
    assertTrue(create.descriptor().inputSchema().required().contains("objective"));
    assertTrue(update.descriptor().inputSchema().required().contains("status"));
    assertTrue(update.descriptor().inputSchema().required().contains("reason"));

    ToolResult created =
        execute(create, 11, "{\"objective\":\"Ship goal tools\",\"tokenBudget\":1200}");
    assertFalse(created.error());
    assertTrue(text(created).contains("Ship goal tools"));

    ToolResult current = execute(get, 11, "{}");
    assertFalse(current.error());
    assertTrue(text(current).contains("Current goal:"));

    ToolResult completed =
        execute(
            update, 11, "{\"status\":\"complete\",\"reason\":\"All acceptance criteria met.\"}");
    assertFalse(completed.error());
    assertEquals(GoalStatus.complete, store.find(11).orElseThrow().status());

    ToolResult secondUpdate =
        execute(update, 11, "{\"status\":\"blocked\",\"reason\":\"Already terminal.\"}");
    assertTrue(secondUpdate.error());
  }

  @Test
  void getGoalReportsNoGoalAndRejectsInvalidArgs() {
    InMemoryGoalStore store = new InMemoryGoalStore();
    GetGoalTool get = new GetGoalTool(store);
    CreateGoalTool create = new CreateGoalTool(store, CLOCK);

    ToolResult empty = execute(get, 9, "{}");
    assertFalse(empty.error());
    assertTrue(text(empty).contains("There is no current goal."));

    ToolResult badBudget = execute(create, 9, "{\"objective\":\"x\",\"tokenBudget\":0}");
    assertTrue(badBudget.error());

    ToolResult blankObjective = execute(create, 9, "{\"objective\":\"   \"}");
    assertTrue(blankObjective.error());

    ToolResult replace = execute(create, 9, "{\"objective\":\"first\"}");
    assertFalse(replace.error());
    ToolResult again = execute(create, 9, "{\"objective\":\"second\"}");
    assertFalse(again.error());
    assertEquals("second", store.find(9).orElseThrow().objective());
  }

  @Test
  void updateRequiresActiveGoalAndNonBlankReason() {
    InMemoryGoalStore store = new InMemoryGoalStore();
    UpdateGoalTool update = new UpdateGoalTool(store, CLOCK);
    CreateGoalTool create = new CreateGoalTool(store, CLOCK);

    ToolResult noGoal = execute(update, 3, "{\"status\":\"complete\",\"reason\":\"done\"}");
    assertTrue(noGoal.error());
    assertTrue(text(noGoal).contains("No goal is set."));

    execute(create, 3, "{\"objective\":\"work\"}");
    ToolResult blankReason = execute(update, 3, "{\"status\":\"complete\",\"reason\":\"   \"}");
    assertTrue(blankReason.error());
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static ToolResult execute(Tool tool, long threadId, String args) {
    AtomicReference<ToolResult> result = new AtomicReference<>();
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", tool.descriptor().name(), args),
            Duration.ofSeconds(5),
            new ToolExecutionContext(1L, threadId)),
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolResult complete) {
            result.set(complete);
          }

          @Override
          public void onError(Throwable error) {
            throw new AssertionError(error);
          }
        });
    return result.get();
  }

  private static final class InMemoryGoalStore implements GoalStore {
    private final Map<Long, ThreadGoal> goals = new ConcurrentHashMap<>();

    @Override
    public Optional<ThreadGoal> find(long threadId) {
      return Optional.ofNullable(goals.get(threadId));
    }

    @Override
    public ThreadGoal createOrReplace(
        long threadId, String objective, Long tokenBudget, Instant now) {
      ThreadGoal goal =
          new ThreadGoal(
              threadId, objective.trim(), tokenBudget, GoalStatus.active, null, now, now);
      goals.put(threadId, goal);
      return goal;
    }

    @Override
    public ThreadGoal updateTerminal(long threadId, GoalStatus status, String reason, Instant now) {
      ThreadGoal current = goals.get(threadId);
      if (current == null) {
        throw new IllegalStateException("No goal is set.");
      }
      if (current.status() != GoalStatus.active) {
        throw new IllegalStateException(
            "Goal status is " + current.status().name() + "; it cannot be updated by the model.");
      }
      ThreadGoal next =
          new ThreadGoal(
              threadId,
              current.objective(),
              current.tokenBudget(),
              status,
              reason.trim(),
              current.createdAt(),
              now);
      goals.put(threadId, next);
      return next;
    }
  }
}
