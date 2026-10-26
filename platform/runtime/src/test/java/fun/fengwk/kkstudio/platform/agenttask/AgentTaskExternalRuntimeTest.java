package fun.fengwk.kkstudio.platform.agenttask;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 基于真实 {@link HarnessRuntime} 和 {@link InMemoryHarnessStore} 的外部任务身份与重放语义测试：
 * 验证配置变更/删除不触发物化重入、外部字段变化拒绝、并发相同外部请求回放不产生额外 commands。
 */
class AgentTaskExternalRuntimeTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("test-agent", ModelSelection.model("provider", "model"), null);

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;
  private AgentTaskService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, (tid, path, prep) -> null, () -> CompactionConfig.DEFAULT);
    ObjectProvider<HarnessRuntime> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(runtime);
    service = new AgentTaskService(provider);
  }

  /** 首次提交正常物化；再次以相同外部身份提交重放时，即使配置被删除或变更， 也不再调用物化器，直接基于真实运行时既有票据完成幂等重放；未存在的新任务配置缺失则明确失败。 */
  @Test
  void replayWithExistingTicketDoesNotMaterializeEvenIfConfigChangedOrDeleted() {
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    String agent = "test-agent";
    String prompt = "hello world";
    String requestHash = AgentTaskRequest.requestHash(ticketId, agent, prompt, null);

    AtomicInteger materializeCalls = new AtomicInteger();
    AtomicBoolean configDeleted = new AtomicBoolean(false);

    Supplier<AgentTaskRequest> supplier =
        () -> {
          if (configDeleted.get()) {
            throw new IllegalStateException("agent configuration was deleted");
          }
          materializeCalls.incrementAndGet();
          NewThreadCommand command =
              new NewThreadCommand(
                  new CustomMessageCommandPayload(AgentMessage.user(prompt)), UUID.randomUUID());
          return new AgentTaskRequest(
              ticketId,
              threadId,
              sessionId,
              SETTINGS,
              List.of(command),
              AcceptancePreflight.IDENTITY,
              SpawnTrigger.CONTROLLER,
              null,
              null,
              null,
              false,
              new SpawnRequest.Budget(null, 1, 1, 10),
              requestHash);
        };

    // 首次提交：执行物化并在真实 runtime spawn
    UUID spawnedThreadId = service.submitExternal(ticketId, requestHash, supplier);
    assertEquals(threadId, spawnedThreadId);
    assertEquals(1, materializeCalls.get());

    // 真实运行时中已记录该票据
    Optional<ThreadJoin> join = runtime.findJoin(ticketId);
    assertTrue(join.isPresent());
    assertEquals(requestHash, join.get().requestHash());

    // 模拟配置已被删除
    configDeleted.set(true);

    // 再次提交相同请求（相同 ticketId 与 requestHash）
    UUID replayedThreadId = service.submitExternal(ticketId, requestHash, supplier);
    assertEquals(threadId, replayedThreadId);
    assertEquals(1, materializeCalls.get(), "materialize count must remain 1 during replay");

    // 未存在的新票据在配置删除时必须明确抛错失败
    UUID newTicketId = UUID.randomUUID();
    String newRequestHash = AgentTaskRequest.requestHash(newTicketId, agent, prompt, null);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.submitExternal(newTicketId, newRequestHash, supplier));
    assertEquals("agent configuration was deleted", failure.getMessage());
  }

  /** 既有票据身份复用时，若外部字段（prompt、agent、maxTurns）与初始指纹不一致， 必须拒绝执行并保留原有运行时状态不变。 */
  @Test
  void submitWithExistingTicketRejectsDifferentFieldsAndPreservesExistingState() {
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    String agent = "test-agent";
    String prompt = "hello world";
    String requestHash = AgentTaskRequest.requestHash(ticketId, agent, prompt, null);

    service.submitExternal(
        ticketId,
        requestHash,
        () ->
            new AgentTaskRequest(
                ticketId,
                threadId,
                sessionId,
                SETTINGS,
                List.of(
                    new NewThreadCommand(
                        new CustomMessageCommandPayload(AgentMessage.user(prompt)),
                        UUID.randomUUID())),
                AcceptancePreflight.IDENTITY,
                SpawnTrigger.CONTROLLER,
                null,
                null,
                null,
                false,
                new SpawnRequest.Budget(null, 1, 1, 10),
                requestHash));

    // 变更 prompt 拒绝
    String diffPromptHash = AgentTaskRequest.requestHash(ticketId, agent, "different prompt", null);
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.submitExternal(
                    ticketId,
                    diffPromptHash,
                    () -> {
                      throw new AssertionError("supplier must not run");
                    }));
    assertEquals("join invocation identity reused", ex1.getMessage());

    // 变更 agent 拒绝
    String diffAgentHash = AgentTaskRequest.requestHash(ticketId, "other-agent", prompt, null);
    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.submitExternal(
                    ticketId,
                    diffAgentHash,
                    () -> {
                      throw new AssertionError("supplier must not run");
                    }));
    assertEquals("join invocation identity reused", ex2.getMessage());

    // 变更 maxTurns 拒绝
    String diffTurnsHash = AgentTaskRequest.requestHash(ticketId, agent, prompt, 5);
    IllegalArgumentException ex3 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.submitExternal(
                    ticketId,
                    diffTurnsHash,
                    () -> {
                      throw new AssertionError("supplier must not run");
                    }));
    assertEquals("join invocation identity reused", ex3.getMessage());

    // 验证原 Runtime 中 ticket 状态依然完好无损
    Optional<ThreadJoin> join = runtime.findJoin(ticketId);
    assertTrue(join.isPresent());
    assertEquals(threadId, join.get().childThreadId());
    assertEquals(requestHash, join.get().requestHash());
  }

  /**
   * 并发场景下，各线程均通过首次票据检查并进入 supplier 完成各自不同 settings 的物化。 随后同时尝试 spawn，后到达的在真实 Runtime 触发
   * THREAD_ID_REUSED 冲突后二次复核票据通过完成回放， 最终 Thread 上严格只有 1 条命令；后续再次重放命令数仍保持为 1。
   */
  @Test
  void concurrentSubmissionsWithDifferentSettingsReplayWithoutDuplicateCommands() throws Exception {
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    String agent = "test-agent";
    String prompt = "concurrent task";
    String requestHash = AgentTaskRequest.requestHash(ticketId, agent, prompt, null);

    BranchSettings settingsA =
        new BranchSettings("test-agent", ModelSelection.model("provider-a", "model-a"), null);
    BranchSettings settingsB =
        new BranchSettings("test-agent", ModelSelection.model("provider-b", "model-b"), null);

    Supplier<AgentTaskRequest> supplierA =
        () ->
            new AgentTaskRequest(
                ticketId,
                threadId,
                sessionId,
                settingsA,
                List.of(
                    new NewThreadCommand(
                        new CustomMessageCommandPayload(AgentMessage.user(prompt)), commandId)),
                AcceptancePreflight.IDENTITY,
                SpawnTrigger.CONTROLLER,
                null,
                null,
                null,
                false,
                new SpawnRequest.Budget(null, 1, 1, 10),
                requestHash);

    Supplier<AgentTaskRequest> supplierB =
        () ->
            new AgentTaskRequest(
                ticketId,
                threadId,
                sessionId,
                settingsB,
                List.of(
                    new NewThreadCommand(
                        new CustomMessageCommandPayload(AgentMessage.user(prompt)), commandId)),
                AcceptancePreflight.IDENTITY,
                SpawnTrigger.CONTROLLER,
                null,
                null,
                null,
                false,
                new SpawnRequest.Budget(null, 1, 1, 10),
                requestHash);

    int threadCount = 4;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier supplierBarrier = new CyclicBarrier(threadCount);
      List<Callable<UUID>> tasks = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        Supplier<AgentTaskRequest> baseSupplier = (i % 2 == 0) ? supplierA : supplierB;
        Supplier<AgentTaskRequest> synchronizedSupplier =
            () -> {
              try {
                supplierBarrier.await(5, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return baseSupplier.get();
            };
        tasks.add(() -> service.submitExternal(ticketId, requestHash, synchronizedSupplier));
      }

      List<Future<UUID>> futures = executor.invokeAll(tasks);
      for (Future<UUID> future : futures) {
        UUID returnedId = future.get(5, TimeUnit.SECONDS);
        assertEquals(threadId, returnedId);
      }
    } finally {
      executor.shutdownNow();
    }

    // 验证真实 Runtime 中的命令：该 Thread 上的命令严格为 1 条，无重复/额外命令
    List<ThreadCommand> commands = store.transaction(tx -> loadCommandsByThread(tx, threadId));
    assertEquals(1, commands.size());
    assertEquals(commandId, commands.getFirst().idempotencyKey());

    // 首次并发完成后的后续重放：仍直接命中既有票据，命令数严格保持为 1
    UUID replayedId = service.submitExternal(ticketId, requestHash, supplierA);
    assertEquals(threadId, replayedId);
    List<ThreadCommand> commandsAfterReplay =
        store.transaction(tx -> loadCommandsByThread(tx, threadId));
    assertEquals(1, commandsAfterReplay.size());
  }
}
