package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasSession;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

class HarnessCommandAcceptanceOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID CANVAS_ID = id(2);
  private static final UUID SESSION_ID = id(3);
  private static final UUID THREAD_ID = id(4);
  private static final UUID ENTRY_ID = id(5);
  private static final UUID UPLOAD_ID = id(6);
  private static final UUID BLOB_ID = id(7);
  private static final OwnerRef CHAT_OWNER = new OwnerRef(OwnerType.CHAT, CHAT_ID);
  private static final OwnerRef CANVAS_OWNER = new OwnerRef(OwnerType.CANVAS, CANVAS_ID);
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"));

  private ChatSessionRepository chatSessionRepository;
  private CanvasSessionRepository canvasSessionRepository;
  private ChatRepository chatRepository;
  private CanvasStore canvasStore;
  private ObjectProvider<HarnessStore> stores;
  private ObjectProvider<HarnessRuntime> runtimes;
  private ObjectProvider<StorageUploadService> uploadServices;
  private ObjectProvider<SessionBlobRefManager> refManagers;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private HarnessRuntime runtime;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private AcceptedCommands accepted;
  private HarnessCommandAcceptanceOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    canvasSessionRepository = mock(CanvasSessionRepository.class);
    chatRepository = mock(ChatRepository.class);
    canvasStore = mock(CanvasStore.class);
    stores = mock(ObjectProvider.class);
    runtimes = mock(ObjectProvider.class);
    uploadServices = mock(ObjectProvider.class);
    refManagers = mock(ObjectProvider.class);
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    runtime = mock(HarnessRuntime.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    accepted = mock(AcceptedCommands.class);

    when(stores.getIfAvailable()).thenReturn(store);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(uploadServices.getIfAvailable()).thenReturn(uploadService);
    when(refManagers.getIfAvailable()).thenReturn(refManager);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    when(transaction.findSession(any())).thenReturn(Optional.empty());
    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(mock(Chat.class));
    when(canvasStore.lockDocumentForKeyShare(CANVAS_ID))
        .thenReturn(Optional.of(mock(CanvasDocument.class)));
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    service =
        new HarnessCommandAcceptanceOrchestrator(
            chatSessionRepository,
            canvasSessionRepository,
            chatRepository,
            canvasStore,
            stores,
            runtimes,
            uploadServices,
            refManagers);
  }

  @Test
  void createsChatAndCanvasOwnershipAndPreservesNonUserCommands() {
    // NEW_SESSION preflight 只创建对应 owner relation；SET_* 保持同一命令实例，USER 文本保持幂等键。
    NewThreadCommand setAgent =
        new NewThreadCommand(new SetAgentCommandPayload("assistant"), id(20));
    NewThreadCommand user = user(new TextMessageContent("hello"));
    AcceptCommandsCommand chatCommand = newSession(setAgent, user);
    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(1);

    AcceptancePreflight chatPreflight = acceptAndCapturePreflight(CHAT_OWNER, chatCommand);
    List<NewThreadCommand> chatPrepared =
        chatPreflight.prepare(
            transaction, new Session(SESSION_ID, "session", NOW), chatCommand.commands());

    assertSame(setAgent, chatPrepared.getFirst());
    assertEquals(user.idempotencyKey(), chatPrepared.get(1).idempotencyKey());
    assertEquals(user.requestHash(), chatPrepared.get(1).requestHash());
    UserMessageCommandPayload mapped =
        assertInstanceOf(UserMessageCommandPayload.class, chatPrepared.get(1).payload());
    assertEquals("hello", ((TextMessageContent) mapped.message().contents().getFirst()).text());
    verify(chatSessionRepository).insertIfNotOwnedByOther(SESSION_ID, CHAT_ID);

    AcceptCommandsCommand canvasCommand = newSession(user(new TextMessageContent("canvas")));
    when(canvasSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CANVAS_ID)).thenReturn(1);
    AcceptancePreflight canvasPreflight = acceptAndCapturePreflight(CANVAS_OWNER, canvasCommand);
    canvasPreflight.prepare(
        transaction, new Session(SESSION_ID, "session", NOW), canvasCommand.commands());

    verify(canvasSessionRepository).insertIfNotOwnedByOther(SESSION_ID, CANVAS_ID);
  }

  @Test
  void authorizesExistingNewSessionEntryAndThreadTargetsForTheirOwners() {
    // 已存在 Session、NEW_THREAD 与 THREAD 都必须先锁 owner，再验证对应 relation。
    when(transaction.findSession(SESSION_ID))
        .thenReturn(Optional.of(new Session(SESSION_ID, "session", NOW)));
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, CHAT_ID));
    AcceptCommandsCommand replay = newSession(user(new TextMessageContent("replay")));

    assertSame(accepted, service.accept(CHAT_OWNER, replay));

    AcceptCommandsCommand entry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("entry"))));
    assertSame(accepted, service.accept(CHAT_OWNER, entry));

    ThreadState thread = thread(THREAD_ID, SESSION_ID);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread));
    when(canvasSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new CanvasSession(SESSION_ID, CANVAS_ID));
    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("thread"))));
    assertSame(accepted, service.accept(CANVAS_OWNER, threadCommand));

    verify(chatRepository, times(2)).lockForKeyShare(CHAT_ID);
    verify(canvasStore).lockDocumentForKeyShare(CANVAS_ID);
    verify(chatSessionRepository, times(2)).findBySessionId(SESSION_ID);
    verify(canvasSessionRepository).findBySessionId(SESSION_ID);
  }

  @Test
  void rejectsMissingOwnersBeforeRuntimeAcceptance() {
    // Owner 行缺失时不能读取或写入 Runtime acceptance。
    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("missing chat")))));

    when(canvasStore.lockDocumentForKeyShare(CANVAS_ID)).thenReturn(Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                CANVAS_OWNER, newSession(user(new TextMessageContent("missing canvas")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsMissingMismatchedOrUnownedSessionsAndThreads() {
    // relation 缺失与 owner 不匹配都 fail closed；THREAD 不存在时不能猜测 Session。
    AcceptCommandsCommand chatEntry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("entry"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, chatEntry));

    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, id(99)));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, chatEntry));

    AcceptCommandsCommand canvasEntry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("canvas entry"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CANVAS_OWNER, canvasEntry));
    when(canvasSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new CanvasSession(SESSION_ID, id(98)));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CANVAS_OWNER, canvasEntry));

    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("thread"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, threadCommand));

    when(transaction.findSession(SESSION_ID))
        .thenReturn(Optional.of(new Session(SESSION_ID, "session", NOW)));
    when(chatSessionRepository.findBySessionId(SESSION_ID)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("orphan replay")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsOwnershipInsertConflictsAndUnexpectedRowCounts() {
    // Relation SQL 必须精确插入一行；另一 owner 已持有或异常行数都回滚 acceptance。
    AcceptCommandsCommand command = newSession(user(new TextMessageContent("ownership")));
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);
    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(0);

    assertThrows(
        IllegalStateException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(2);
    assertThrows(
        IllegalStateException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));
  }

  @Test
  void materializesAttachmentBeforeDeletingUploadAndPreservesRawIdentity() {
    // READY upload 先建立 Session retain，再删除 upload；durable payload 替换但 raw 幂等键不变。
    NewThreadCommand raw = user(new AttachmentMessageContent(UPLOAD_ID));
    AcceptCommandsCommand command = newSession(raw);
    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(1);
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "report.pdf"));
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    List<NewThreadCommand> prepared =
        preflight.prepare(transaction, new Session(SESSION_ID, "session", NOW), command.commands());

    NewThreadCommand durable = prepared.getFirst();
    assertEquals(raw.idempotencyKey(), durable.idempotencyKey());
    assertEquals(raw.requestHash(), durable.requestHash());
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, durable.payload());
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, payload.message().contents().getFirst());
    assertEquals(BLOB_ID, resource.blobId());
    assertEquals("report.pdf", resource.name());
    InOrder order = inOrder(refManager, uploadService);
    order.verify(refManager).retainRef(SESSION_ID, BLOB_ID);
    order.verify(uploadService).delete(UPLOAD_ID);
  }

  @Test
  void rejectsAttachmentWhenStorageDependenciesAreMissing() {
    // Upload service 与 Session ref manager 任一缺失都不能消费瞬时 attachment。
    AcceptCommandsCommand command = newSession(user(new AttachmentMessageContent(UPLOAD_ID)));
    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(1);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    when(uploadServices.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    when(uploadServices.getIfAvailable()).thenReturn(uploadService);
    when(refManagers.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));
    verifyNoInteractions(uploadService);
  }

  @Test
  void translatesAttachmentLookupAndVerificationFailures() {
    // Storage 的 not-found 与 verification 失败都统一成为非法用户内容，且不得 retain/delete。
    AcceptCommandsCommand command = newSession(user(new AttachmentMessageContent(UPLOAD_ID)));
    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(1);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);
    doThrow(new StorageResourceNotFoundException("upload", UPLOAD_ID.toString()))
        .when(uploadService)
        .lockReady(UPLOAD_ID);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    doThrow(new StorageVerificationException("upload is pending"))
        .when(uploadService)
        .lockReady(UPLOAD_ID);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));
    verify(refManager, never()).retainRef(any(), any());
    verify(uploadService, never()).delete(any());
  }

  @Test
  void reusesOnlyResourcesAlreadyOwnedByTheSession() {
    // Durable RESOURCE 不新增 retain；缺 manager 或跨 Session blob 都明确拒绝。
    ResourceMessageContent resource =
        new ResourceMessageContent(BLOB_ID, "existing.txt", "preview");
    AcceptCommandsCommand command = newSession(user(resource));
    when(chatSessionRepository.insertIfNotOwnedByOther(SESSION_ID, CHAT_ID)).thenReturn(1);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    when(refManagers.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    when(refManagers.getIfAvailable()).thenReturn(refManager);
    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(true);
    List<NewThreadCommand> prepared =
        preflight.prepare(transaction, new Session(SESSION_ID, "session", NOW), command.commands());
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, prepared.getFirst().payload());
    assertSame(resource, payload.message().contents().getFirst());
    verify(refManager, never()).retainRef(any(), any());
  }

  @Test
  void requiresRuntimeAndStoreBeforeAcceptance() {
    // 缺 Runtime 或 Store 时部署必须 fail closed，不能返回伪成功。
    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalStateException.class,
        () -> service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("runtime")))));

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(stores.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalStateException.class,
        () -> service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("store")))));
    verify(runtime, never()).acceptCommands(any(), any());
  }

  private AcceptancePreflight acceptAndCapturePreflight(
      OwnerRef owner, AcceptCommandsCommand command) {
    assertSame(accepted, service.accept(owner, command));
    ArgumentCaptor<AcceptancePreflight> captor = ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).acceptCommands(eq(command), captor.capture());
    return captor.getValue();
  }

  private static AcceptCommandsCommand newSession(NewThreadCommand... commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(SESSION_ID, THREAD_ID, SETTINGS, null, false),
        List.of(commands));
  }

  private static NewThreadCommand user(AgentMessageContent... contents) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(contents))),
        id(30));
  }

  private static ThreadState thread(UUID threadId, UUID sessionId) {
    return new ThreadState(
        threadId, sessionId, ENTRY_ID, "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
