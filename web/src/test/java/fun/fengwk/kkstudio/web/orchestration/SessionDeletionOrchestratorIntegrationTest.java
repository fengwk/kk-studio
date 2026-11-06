package fun.fengwk.kkstudio.web.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.chat.Chat;
import fun.fengwk.kkstudio.chat.ChatRepository;
import fun.fengwk.kkstudio.chat.ChatService;
import fun.fengwk.kkstudio.chat.ChatSessionRepository;
import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.StorageUploadTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Session 深删除在 web 组合根的真实 PostgreSQL/S3 契约：跨 Session 执行树闭包、锁序、Driver 与 Blob 引用清理和回滚。
 *
 * <p>使用真实组合根装配与 PostgreSQL 外键路径驱动编排器（{@link SessionDeletionOrchestrator}），闭包场景由测试提供外层事务，
 * 与生产调用方（{@code @Transactional} 入口）语义保持一致。
 *
 * <p>测试意图（断言真实 store 中的行数，不依赖 mock 调用顺序）：
 *
 * <ul>
 *   <li>归属 owner 的所有 Session/Thread（含 ACTIVE 与已停下的 IDLE）随 task 子 Session 与共享 Session 内的无父 fork
 *       一起删除；未匹配的 pending join 与已匹配并交付的 join 因父子两端都在删除集合内而一并删除；
 *   <li>不属于该 owner 的 Session/Thread/Join/Command 完全不受影响（防全量删除破坏生存者）；
 *   <li>闭包被截断（待删 Thread 的执行父仍在生存 Session 中）时必须整体回滚，绝不留下悬挂 join 或半删子树；
 *   <li>owner 未持有任何 Thread 的空 Session 同样可被深删除；
 *   <li>Session 与 Thread 锁序、Driver 私有行清理和跨 owner 共享 Blob 引用均正确收敛。
 * </ul>
 */
@Import(WebStorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class SessionDeletionOrchestratorIntegrationTest extends WebPostgresTestSupport {

  private static final Instant T0 = Instant.ofEpochMilli(1000);
  private static final Instant T1 = Instant.ofEpochMilli(2000);
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", BuiltinExecution.of(new ModelSelection("model", "v1")), null);

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ChatService chatService;
  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private HarnessStore store;
  @Autowired private HarnessCommandAcceptanceOrchestrator acceptanceService;
  @Autowired private SessionDeletionOrchestrator orchestrator;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  private TransactionTemplate transactions;
  private StorageUploadTestSupport storage;

  @BeforeEach
  void setUp() {
    transactions = new TransactionTemplate(transactionManager);
    s3Storage.clear();
    storage = new StorageUploadTestSupport(storageUploadService, s3Storage, jdbc);
  }

  /** 深删只释放目标 owner 的 Blob 引用，另一 owner 的 Session 与对象保留。 */
  @Test
  void deepDeleteReleasesOnlyTargetOwnerBlobRefs() {
    UUID chat1Id = createChat("delete-chat-1");
    UUID chat2Id = createChat("delete-chat-2");
    byte[] content = "shared blob".getBytes(StandardCharsets.UTF_8);
    String upload1 = storage.completeUpload("chat1.txt", content);
    String upload2 = storage.completeUpload("chat2.txt", content);
    UUID session1 = UUID.randomUUID();
    UUID session2 = UUID.randomUUID();
    UUID thread1 = UUID.randomUUID();
    UUID thread2 = UUID.randomUUID();

    acceptAttachment(
        new SessionOwner("CHAT", chat1Id.toString()), session1, thread1, UUID.fromString(upload1));
    acceptAttachment(
        new SessionOwner("CHAT", chat2Id.toString()), session2, thread2, UUID.fromString(upload2));

    UUID blobId =
        jdbc.queryForObject(
            "select blob_id from session_blob_ref where session_id = ?", UUID.class, session1);
    assertEquals(
        2L,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));

    orchestrator.deleteSessionsByOwner(new SessionOwner("CHAT", chat1Id.toString()));

    assertEquals(0, count("chat_session", "session_id", session1));
    assertEquals(0, count("harness_session", "id", session1));
    assertEquals(0, count("harness_thread", "id", thread1));
    assertEquals(0, count("session_blob_ref", "session_id", session1));
    assertEquals(1, count("chat_session", "session_id", session2));
    assertEquals(1, count("harness_session", "id", session2));
    assertEquals(1, count("harness_thread", "id", thread2));
    assertEquals(1, count("session_blob_ref", "session_id", session2));
    assertEquals(
        1L,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)),
        "the shared blob must remain while the other Chat Session still references it");
  }

  /** 归属枚举顺序与 Session、Thread 锁顺序不同时仍按两个 rank 分别全局排序。 */
  @Test
  void deletionLocksAllSessionsBeforeThreadsWhenRelationOrderDiffers() {
    UUID chatId = createChat("ordered-delete");
    UUID firstSession = new UUID(0L, 2L);
    UUID secondSession = new UUID(0L, 1L);
    UUID firstThread = new UUID(0L, 3L);
    UUID secondThread = new UUID(0L, 4L);
    acceptText(new SessionOwner("CHAT", chatId.toString()), firstSession, firstThread, "first");
    acceptText(new SessionOwner("CHAT", chatId.toString()), secondSession, secondThread, "second");

    jdbc.update(
        "update chat_session set created_at = ? where session_id = ?",
        Timestamp.from(Instant.parse("2026-08-20T00:01:00Z")),
        firstSession);
    jdbc.update(
        "update chat_session set created_at = ? where session_id = ?",
        Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        secondSession);

    // 归属枚举与 Session UUID 顺序相反，按 Session 分组后的 Thread 又是 4 -> 3。
    assertEquals(
        List.of(firstSession, secondSession), chatSessionRepository.listSessionIds(chatId));
    orchestrator.deleteSessionsByOwner(new SessionOwner("CHAT", chatId.toString()));

    assertEquals(0, count("chat_session", "chat_id", chatId));
    assertEquals(0, count("harness_session", "id", firstSession));
    assertEquals(0, count("harness_session", "id", secondSession));
    assertEquals(0, count("harness_thread", "id", firstThread));
    assertEquals(0, count("harness_thread", "id", secondThread));
  }

  /** 深删除先经 Driver 端口清理模型调用行，再删除 Harness Thread，避免外键拒绝。 */
  @Test
  void deepDeleteCleansBuiltinModelInvocationBeforeHarnessRows() {
    UUID chatId = createChat("model-invocation-delete");
    UUID sessionId = new UUID(0L, 21L);
    UUID threadId = new UUID(0L, 22L);
    acceptText(new SessionOwner("CHAT", chatId.toString()), sessionId, threadId, "hello");
    UUID turnStartEntryId =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?", UUID.class, threadId);
    UUID modelInvocationId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    String requestSpec = new ModelRequestSpecJsonCodec().encode(StoreTestSupport.modelRequest());
    jdbc.update(
        "insert into builtin_model_invocation (id, thread_id, turn_start_entry_id,"
            + " request_head_entry_id, request_spec, status, attempt, failed_attempts,"
            + " created_at, updated_at) values (?, ?, ?, ?, ?::jsonb, 'READY', 0,"
            + " '[]'::jsonb, ?, ?)",
        modelInvocationId,
        threadId,
        turnStartEntryId,
        turnStartEntryId,
        requestSpec,
        now,
        now);
    assertEquals(1, count("builtin_model_invocation", "thread_id", threadId));

    orchestrator.deleteSessionsByOwner(new SessionOwner("CHAT", chatId.toString()));

    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("builtin_model_invocation", "thread_id", threadId));
  }

  @Test
  void deletesWholeExecutionTreeAcrossSessionsAndKeepsUnrelatedOwnerIntact() {
    UUID owner = chatOwner();
    UUID otherOwner = chatOwner();
    UUID ownedSession = ownedSession("owned", owner);
    UUID ownedRoot = seedRootThread(ownedSession, ThreadExecutionControl.RUNNABLE);
    UUID sameSessionChild =
        seedChildThread(ownedSession, ownedRoot, ThreadExecutionControl.RUNNABLE);
    UUID childSession = seedSession("task-child");
    UUID childSessionRoot = seedRootEntry(childSession);
    UUID crossSessionChild =
        seedThreadIn(childSession, childSessionRoot, ownedRoot, ThreadExecutionControl.RUNNABLE);
    // 同一 Session 内由另一入口建立的无父 fork：只有「以 Session 为单位」推进闭包才会把它一并删除。
    UUID childSessionFork =
        seedThreadIn(childSession, childSessionRoot, null, ThreadExecutionControl.RUNNABLE);
    UUID emptySession = ownedSession("owned-empty", owner);
    // pending join（未匹配）：逐 child 删除会拒绝，闭包内两端同删必须允许。
    UUID pendingJoin = seedJoin(sameSessionChild, ownedRoot, 1L, null, null);
    // 已匹配并交付：父仍在删除集合内，同样一并回收。
    UUID matchedDelivered =
        seedJoin(sameSessionChild, ownedRoot, 2L, headEntryOf(ownedSession), null);
    // 跨 Session 子线程持有的 pending join：其 Session 由后代闭包覆盖。
    UUID crossSessionJoin = seedJoin(crossSessionChild, ownedRoot, 1L, null, null);

    // 生存者：另一个 owner 的 Session + root + child + pending join。
    UUID survivorSession = ownedSession("survivor", otherOwner);
    UUID survivorRoot = seedRootThread(survivorSession, ThreadExecutionControl.RUNNABLE);
    UUID survivorChild =
        seedChildThread(survivorSession, survivorRoot, ThreadExecutionControl.RUNNABLE);
    UUID survivorJoin = seedJoin(survivorChild, survivorRoot, 1L, null, null);

    deleteSessions(new SessionOwner("CHAT", owner.toString()));

    assertEquals(0L, sessionRows(ownedSession));
    assertEquals(0L, sessionRows(childSession));
    assertEquals(0L, sessionRows(emptySession));
    assertEquals(0L, entryRows(ownedSession));
    assertEquals(0L, entryRows(childSession));
    for (UUID threadId :
        List.of(ownedRoot, sameSessionChild, crossSessionChild, childSessionFork)) {
      assertEquals(0L, threadRows(threadId), "thread " + threadId + " must be deleted");
      assertEquals(0L, commandRows(threadId), "commands of " + threadId + " must be deleted");
    }
    assertEquals(0L, joinRows(pendingJoin));
    assertEquals(0L, joinRows(matchedDelivered));
    assertEquals(0L, joinRows(crossSessionJoin));
    assertEquals(0L, count("select count(*) from chat_session where chat_id = ?", owner));

    // 生存侧：无关 owner 的整棵树（含 pending join 与命令）逐行保留。
    assertEquals(1L, sessionRows(survivorSession));
    assertEquals(1L, threadRows(survivorRoot));
    assertEquals(1L, threadRows(survivorChild));
    assertEquals(1L, joinRows(survivorJoin));
    assertEquals(1L, commandRows(survivorChild));
    assertEquals(1L, count("select count(*) from chat_session where chat_id = ?", otherOwner));
  }

  @Test
  void truncatedClosureRollsBackWithoutTouchingSurvivors() {
    UUID owner = chatOwner();
    UUID otherOwner = chatOwner();
    UUID survivorSession = ownedSession("survivor", otherOwner);
    UUID survivorRoot = seedRootThread(survivorSession, ThreadExecutionControl.RUNNABLE);
    UUID ownedSession = ownedSession("owned", owner);
    // 该 Session 内的线程指向生存者 Session 的父：闭包只能是它自己，父不在删除集合内。
    UUID strandedChild =
        seedThreadIn(
            ownedSession,
            seedRootEntry(ownedSession),
            survivorRoot,
            ThreadExecutionControl.RUNNABLE);
    UUID strandedJoin = seedJoin(strandedChild, survivorRoot, 1L, null, null);

    assertThrows(
        IllegalStateException.class,
        () -> deleteSessions(new SessionOwner("CHAT", owner.toString())));

    // 全部回滚：归属行、Session、Thread、Join 都与删除前一致。
    assertEquals(1L, sessionRows(ownedSession));
    assertEquals(1L, threadRows(strandedChild));
    assertEquals(1L, joinRows(strandedJoin));
    assertEquals(1L, sessionRows(survivorSession));
    assertEquals(1L, threadRows(survivorRoot));
    assertEquals(1L, count("select count(*) from chat_session where chat_id = ?", owner));
  }

  @Test
  void deletesOwnedSessionWithoutThreads() {
    UUID owner = chatOwner();
    UUID emptySession = ownedSession("owned-empty", owner);

    deleteSessions(new SessionOwner("CHAT", owner.toString()));

    assertEquals(0L, sessionRows(emptySession));
    assertEquals(0L, entryRows(emptySession));
    assertEquals(0L, count("select count(*) from chat_session where chat_id = ?", owner));
  }

  /** 与生产调用方一致：深删除在调用方的事务内执行，失败整体回滚。 */
  private void deleteSessions(SessionOwner owner) {
    transactions.executeWithoutResult(status -> orchestrator.deleteSessionsByOwner(owner));
  }

  // ---------- fixtures ----------

  private void acceptText(SessionOwner owner, UUID sessionId, UUID threadId, String text) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
                    UUID.randomUUID()))));
  }

  private void acceptAttachment(SessionOwner owner, UUID sessionId, UUID threadId, UUID uploadId) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(new AttachmentMessageContent(uploadId)))),
                    UUID.randomUUID()))));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        "default-assistant",
        BuiltinExecution.of(new ModelSelection("acceptance-stub", "default")),
        null);
  }

  private UUID createChat(String title) {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle(title);
    create.setAgentName("default-assistant");
    return UUID.fromString(chatService.createChat(create).getId());
  }

  /** 建一个 Chat owner 行并返回 id。 */
  private UUID chatOwner() {
    UUID id = UUID.randomUUID();
    Chat chat = new Chat();
    chat.setId(id);
    chat.setTitle("owner-" + id);
    chat.setAgentName("schema-agent");
    chat.setVersion(0L);
    assertTrue(chatRepository.create(chat));
    return id;
  }

  /** owner 持有的 Session：harness_session 行 + chat_session 归属行。 */
  private UUID ownedSession(String name, UUID owner) {
    UUID sessionId = seedSession(name);
    chatSessionRepository.insert(sessionId, owner);
    return sessionId;
  }

  private UUID seedSession(String name) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, name, T0));
          return sessionId;
        });
  }

  private UUID seedRootThread(UUID sessionId, ThreadExecutionControl status) {
    UUID rootEntryId = seedRootEntry(sessionId);
    return seedThreadIn(sessionId, rootEntryId, null, status);
  }

  /** 在 Session 内新建 ROOT Entry；每个 Session 至多一个（schema 唯一约束）。 */
  private UUID seedRootEntry(UUID sessionId) {
    return store.transaction(
        tx -> {
          UUID rootEntryId = tx.nextId();
          tx.entries()
              .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), T0));
          return rootEntryId;
        });
  }

  private UUID seedChildThread(UUID sessionId, UUID parentThreadId, ThreadExecutionControl status) {
    return seedThreadIn(sessionId, headEntryOf(sessionId), parentThreadId, status);
  }

  private UUID seedThreadIn(
      UUID sessionId, UUID headEntryId, UUID parentThreadId, ThreadExecutionControl status) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          // 根线程使用独立开关；子线程恒 FOLLOW 传入的执行根（fixture 的 parent 即执行根）。
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      parentThreadId,
                      headEntryId,
                      HASH,
                      "thread-" + threadId,
                      parentThreadId == null ? threadId : parentThreadId,
                      false,
                      SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
                      status,
                      0L,
                      1L,
                      0L,
                      T0,
                      T0));
          return threadId;
        });
  }

  /** Session 内已有线程的 head Entry（同 Session 的合法 head，也用作 matched receipt 的结果 head）。 */
  private UUID headEntryOf(UUID sessionId) {
    return store.transaction(
        tx ->
            tx.threads().listBySession(sessionId).stream()
                .map(ThreadState::headEntryId)
                .findFirst()
                .orElseThrow());
  }

  /**
   * 写入一条 join 及其源 command（同一事务，满足 join 对源 command 的外键）。
   *
   * <p>{@code terminalEntryId} 为空表示未匹配的 pending join；非空表示已匹配并交付，需要父命令。
   *
   * @return 该 join 的 invocationId，便于按行断言删除结果
   */
  private UUID seedJoin(
      UUID childThreadId,
      UUID parentThreadId,
      long sequence,
      UUID terminalEntryId,
      UUID finalAnswerEntryId) {
    UUID invocationId = UUID.randomUUID();
    store.transaction(
        tx -> {
          List<UUID> threadsToLock =
              terminalEntryId != null
                  ? Stream.of(childThreadId, parentThreadId).sorted(UuidOrder.COMPARATOR).toList()
                  : List.of(childThreadId);
          threadsToLock.forEach(id -> tx.threads().lock(id));
          tx.commands().insert(List.of(command(childThreadId, sequence)));
          if (terminalEntryId != null) {
            tx.commands().insert(List.of(command(parentThreadId, 1L)));
          }
          ThreadJoin join =
              new ThreadJoin(
                  invocationId,
                  HASH,
                  parentThreadId,
                  childThreadId,
                  sequence,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0,
                  JoinDelivery.NOTIFY_PARENT,
                  null);
          tx.joins().insert(join);
          if (terminalEntryId != null) {
            tx.joins()
                .update(join.match(terminalEntryId, finalAnswerEntryId, T1).delivered(1L, T1));
          }
          return null;
        });
    return invocationId;
  }

  private static ThreadCommand command(UUID threadId, long sequence) {
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent("message " + sequence))));
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        UUID.randomUUID(),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        T0);
  }

  // ---------- row probes ----------

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }

  private long count(String sql, Object... args) {
    Long val = jdbc.queryForObject(sql, Long.class, args);
    return val != null ? val : 0L;
  }

  private long sessionRows(UUID sessionId) {
    return count("select count(*) from harness_session where id = ?", sessionId);
  }

  private long entryRows(UUID sessionId) {
    return count("select count(*) from harness_entry where session_id = ?", sessionId);
  }

  private long threadRows(UUID threadId) {
    return count("select count(*) from harness_thread where id = ?", threadId);
  }

  private long commandRows(UUID threadId) {
    return count("select count(*) from harness_thread_command where thread_id = ?", threadId);
  }

  private long joinRows(UUID invocationId) {
    return count("select count(*) from harness_thread_join where invocation_id = ?", invocationId);
  }
}
