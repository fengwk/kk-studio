package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 意图：C3 控制服务只以权威完整快照授权；显式实例/epoch 围栏、旧 revision 不回退、GET 纯读、结构化 reason 与 OPEN 结果未知时的对账都必须保持精确。 */
class EnvironmentIdeServiceImplTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID BROWSER_SESSION =
      UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");
  private static final UUID ATTACHMENT_ID = UUID.fromString("abcdefab-cdef-4abc-8def-abcdefabcdef");
  private static final UUID ATTACHMENT_SESSION_ID =
      UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final String DAEMON_INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String REPLACEMENT_INSTANCE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String OLD_INSTANCE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  private static final String NEW_INSTANCE = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

  private FakeTransport transport;
  private FakeBrowserUse browserUse;
  private IdeBootstrapTicketService tickets;
  private IdeViewSnapshotStore snapshots;
  private HarnessOwnerQueryService ownerQueryService;
  private SessionBlobRefManager sessionBlobRefManager;
  private StorageBlobRepository blobRepository;
  private StorageBlobContentService blobContentService;
  private EnvironmentIdeServiceImpl service;

  @BeforeEach
  void setUp() {
    transport = new FakeTransport();
    browserUse = new FakeBrowserUse();
    tickets = new IdeBootstrapTicketService();
    snapshots = new IdeViewSnapshotStore(transport);
    ownerQueryService = mock(HarnessOwnerQueryService.class);
    sessionBlobRefManager = mock(SessionBlobRefManager.class);
    blobRepository = mock(StorageBlobRepository.class);
    blobContentService = mock(StorageBlobContentService.class);
    service =
        new EnvironmentIdeServiceImpl(
            transport,
            tickets,
            browserUse,
            snapshots,
            Duration.ofMillis(150),
            ownerQueryService,
            sessionBlobRefManager,
            blobRepository,
            blobContentService);
  }

  @Test
  void openViewUsesExplicitInstanceAndCompleteSnapshot() {
    transport.replies.add(openSnapshot(5L, 7L));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openRequest("create", 0L, "/client/dir"));

    assertEquals(VIEW.toString(), dto.getViewId());
    assertEquals(BROWSER_SESSION.toString(), dto.getBrowserSessionId());
    assertEquals(DAEMON_INSTANCE, dto.getDaemonInstanceId());
    assertEquals(7L, dto.getBindingEpoch());
    assertEquals("/daemon/ws", dto.getWorkspacePath());
    assertTrue(dto.getEntryUrl().contains("?token="));
    assertEquals(1, tickets.pendingCount());

    IdeCommand command = transport.commands.get(0);
    assertEquals(IdeAction.OPEN_VIEW, command.getAction());
    assertEquals(BROWSER_SESSION.toString(), command.getBrowserSessionId());
    assertEquals(0L, command.getExpectedBindingEpoch());
    assertEquals(DAEMON_INSTANCE, command.getExpectedDaemonInstanceId());
    assertEquals(
        List.of(new IntentQuery(EVENT_NODE, CONNECTION, BROWSER_SESSION, VIEW, CLAIM)),
        browserUse.queries);
  }

  @Test
  void openViewRequiresExplicitExpectedDaemonInstanceWithoutFallback() {
    EnvironmentIdeOpenViewDTO request = openRequest("create", 0L, null);
    request.setExpectedDaemonInstanceId(null);

    DomainException error =
        assertThrows(DomainException.class, () -> service.openView(ENV, request));

    assertEquals("validation", error.code().code());
    assertTrue(transport.commands.isEmpty());
  }

  @Test
  void mismatchedExpectedDaemonInstanceIsViewGoneWithoutSendingCommand() {
    EnvironmentIdeOpenViewDTO request = openRequest("create", 0L, null);
    request.setExpectedDaemonInstanceId(REPLACEMENT_INSTANCE);

    DomainException error =
        assertThrows(DomainException.class, () -> service.openView(ENV, request));

    assertEquals("VIEW_GONE", error.reason());
    assertTrue(transport.commands.isEmpty());
  }

  @Test
  void invalidExpectedDaemonInstanceIdIsValidationBeforeAnyCommand() {
    String invalid = "daemon-instance";
    EnvironmentIdeOpenViewDTO open = openRequest("create", 0L, null);
    open.setExpectedDaemonInstanceId(invalid);
    DomainException openError =
        assertThrows(DomainException.class, () -> service.openView(ENV, open));
    assertEquals("validation", openError.code().code());
    assertTrue(transport.commands.isEmpty());

    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    int before = transport.commands.size();

    EnvironmentIdeChangeDirectoryDTO change = new EnvironmentIdeChangeDirectoryDTO();
    change.setBrowserSessionId(BROWSER_SESSION.toString());
    change.setBindingEpoch(7L);
    change.setExpectedDaemonInstanceId(invalid);
    change.setDirectory("/next");
    assertEquals(
        "validation",
        assertThrows(
                DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), change))
            .code()
            .code());

    EnvironmentIdeCloseViewDTO close = closeRequest(false);
    close.setExpectedDaemonInstanceId(invalid);
    assertEquals(
        "validation",
        assertThrows(DomainException.class, () -> service.closeView(ENV, VIEW.toString(), close))
            .code()
            .code());

    EnvironmentIdeViewBindingDTO focus = binding(7L);
    focus.setExpectedDaemonInstanceId(invalid);
    assertEquals(
        "validation",
        assertThrows(
                DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), focus))
            .code()
            .code());

    EnvironmentIdeViewBindingDTO apply = binding(7L);
    apply.setExpectedDaemonInstanceId(invalid);
    assertEquals(
        "validation",
        assertThrows(
                DomainException.class, () -> service.collapseMobile(ENV, VIEW.toString(), apply))
            .code()
            .code());

    assertEquals(before, transport.commands.size());
    assertEquals(1, tickets.pendingCount());
  }

  @Test
  void unknownOpenOutcomeReconcilesWithPureReadAndDoesNotReplayOpen() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    transport.replies.add(openSnapshot(5L, 7L));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openRequest("create", 0L, null));

    assertEquals(7L, dto.getBindingEpoch());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
    assertEquals(1, tickets.pendingCount());
  }

  @Test
  void unavailableReadDuringReconciliationKeepsOutcomeUnknown() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    transport.replies.add(
        IdeResult.newBuilder().setOk(false).setCode("UNAVAILABLE").setMessage("not ready").build());

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("IDE_OUTCOME_UNKNOWN", error.reason());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
  }

  @Test
  void reconciliationPreservesStaleBindingInsteadOfTurningItIntoViewGone() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    UUID differentClaim = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    transport.replies.add(snapshot(5L, summary(7L, differentClaim)));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("STALE_BINDING", error.reason());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void readViewReturnsAuthoritativeSnapshotWithoutEntryUrl() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    transport.replies.add(snapshot(6L, summary(7L, CLAIM)));

    EnvironmentIdeViewSnapshotDTO snapshot =
        service.readView(ENV, VIEW.toString(), readRequest(BROWSER_SESSION));

    assertEquals(6L, snapshot.getRevision());
    assertEquals(DAEMON_INSTANCE, snapshot.getDaemonInstanceId());
    assertEquals(VIEW.toString(), snapshot.getView().getViewId());
    assertNull(snapshot.getView().getEntryUrl(), "GET must never issue a bootstrap entry URL");
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
  }

  @Test
  void readViewReturnsAbsentSnapshotAsAValidFact() {
    transport.replies.add(snapshot(4L));

    EnvironmentIdeViewSnapshotDTO snapshot =
        service.readView(ENV, VIEW.toString(), readRequest(BROWSER_SESSION));

    assertEquals(4L, snapshot.getRevision());
    assertNull(snapshot.getView());
  }

  @Test
  void readViewRequiresCurrentAcknowledgedIntentBeforeTouchingDaemon() {
    browserUse.matched = false;

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.readView(ENV, VIEW.toString(), readRequest(BROWSER_SESSION)));

    assertEquals("STALE_BINDING", error.reason());
    assertTrue(transport.commands.isEmpty());
  }

  @Test
  void readViewReportsOtherOwner() {
    UUID otherBrowserSession = UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000");
    transport.replies.add(snapshot(5L, summary(7L, CLAIM)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.readView(ENV, VIEW.toString(), readRequest(otherBrowserSession)));

    assertEquals("VIEW_OWNED_BY_OTHER_SESSION", error.reason());
  }

  @Test
  void staleEpochAndAbsentViewKeepDistinctReasons() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));

    EnvironmentIdeViewBindingDTO wrongEpoch = binding(8L);
    DomainException stale =
        assertThrows(
            DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), wrongEpoch));
    assertEquals("STALE_BINDING", stale.reason());

    EnvironmentIdeChangeDirectoryDTO change = new EnvironmentIdeChangeDirectoryDTO();
    change.setBrowserSessionId(BROWSER_SESSION.toString());
    change.setBindingEpoch(7L);
    change.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    change.setDirectory("/next");
    transport.replies.add(snapshot(7L));
    DomainException gone =
        assertThrows(
            DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), change));
    assertEquals("VIEW_GONE", gone.reason());
  }

  @Test
  void lowerRevisionReceiptCannotRemoveNewBinding() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));

    // Daemon 迟到的低 revision 快照仍然没有该 view：接受结果必须保持当前高 revision 绑定。
    transport.replies.add(snapshot(4L));
    EnvironmentIdeViewsDTO listed = service.listViews(ENV);

    assertEquals(5L, listed.getRevision());
    assertEquals(1, listed.getViews().size());
    assertEquals(7L, listed.getViews().get(0).getBindingEpoch());
  }

  @Test
  void changeDirectoryUsesReplySnapshotAndDeleteDoesNotReadBack() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    EnvironmentIdeChangeDirectoryDTO change = new EnvironmentIdeChangeDirectoryDTO();
    change.setBrowserSessionId(BROWSER_SESSION.toString());
    change.setBindingEpoch(7L);
    change.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    change.setDirectory("/next");
    transport.replies.add(snapshot(6L, summary(7L, CLAIM, "/daemon/next")));

    EnvironmentIdeViewDTO changed = service.changeDirectory(ENV, VIEW.toString(), change);

    assertEquals("/daemon/next", changed.getDirectory());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.CHANGE_DIRECTORY), actions());
  }

  @Test
  void openAttachmentSendsBoundTextWithResourceIdentity() {
    prepareAttachment("hello attachment".getBytes(StandardCharsets.UTF_8), "text/plain");

    service.openAttachment(
        ENV,
        VIEW.toString(),
        ATTACHMENT_SESSION_ID.toString(),
        ATTACHMENT_ID.toString(),
        binding(7L));

    IdeCommand command = transport.commands.get(1);
    assertEquals(IdeAction.OPEN_ATTACHMENT, command.getAction());
    assertEquals(ATTACHMENT_ID.toString(), command.getAttachmentId());
    assertEquals("hello attachment", command.getAttachmentText());
    assertFalse(command.hasDirectory());
    assertFalse(command.hasDiscard());
    verify(ownerQueryService).requireOwnedSession(ATTACHMENT_SESSION_ID);
  }

  @Test
  void openAttachmentRejectsOversizeBeforeCommandDelivery() {
    prepareAttachment(new byte[64 * 1024 + 1], "text/plain");

    assertThrows(
        DomainException.class,
        () ->
            service.openAttachment(
                ENV,
                VIEW.toString(),
                ATTACHMENT_SESSION_ID.toString(),
                ATTACHMENT_ID.toString(),
                binding(7L)));

    assertEquals(List.of(IdeAction.OPEN_VIEW), actions());
  }

  @Test
  void openAttachmentRequiresOwnedSessionAndSessionResourceReference() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    doThrow(DomainException.notFound("session"))
        .when(ownerQueryService)
        .requireOwnedSession(ATTACHMENT_SESSION_ID);

    assertThrows(
        DomainException.class,
        () ->
            service.openAttachment(
                ENV,
                VIEW.toString(),
                ATTACHMENT_SESSION_ID.toString(),
                ATTACHMENT_ID.toString(),
                binding(7L)));

    verify(sessionBlobRefManager, never()).contains(ATTACHMENT_SESSION_ID, ATTACHMENT_ID);
    assertEquals(List.of(IdeAction.OPEN_VIEW), actions());
  }

  @Test
  void openAttachmentRejectsUnsupportedMediaBeforeContentRead() {
    prepareAttachment(new byte[] {'x'}, "application/octet-stream");

    assertThrows(
        DomainException.class,
        () ->
            service.openAttachment(
                ENV,
                VIEW.toString(),
                ATTACHMENT_SESSION_ID.toString(),
                ATTACHMENT_ID.toString(),
                binding(7L)));

    verifyNoInteractions(blobContentService);
    assertEquals(List.of(IdeAction.OPEN_VIEW), actions());
  }

  @Test
  void openAttachmentRejectsInvalidUtf8BeforeCommandDelivery() {
    prepareAttachment(new byte[] {(byte) 0xc3, (byte) 0x28}, "text/plain");

    assertThrows(
        DomainException.class,
        () ->
            service.openAttachment(
                ENV,
                VIEW.toString(),
                ATTACHMENT_SESSION_ID.toString(),
                ATTACHMENT_ID.toString(),
                binding(7L)));

    assertEquals(List.of(IdeAction.OPEN_VIEW), actions());
  }

  @Test
  void openAttachmentRejectsNonCanonicalResourceBeforeAnyLookup() {
    assertThrows(
        DomainException.class,
        () ->
            service.openAttachment(
                ENV,
                VIEW.toString(),
                ATTACHMENT_SESSION_ID.toString(),
                ATTACHMENT_ID.toString().toUpperCase(),
                binding(7L)));

    assertTrue(transport.commands.isEmpty());
  }

  @Test
  void boundCommandsCarryBrowserSessionAndEpochAndCloseRevokesTicket() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    EnvironmentIdeViewBindingDTO binding = binding(7L);
    transport.replies.add(snapshot(6L, summary(7L, CLAIM)));
    service.focusTerminal(ENV, VIEW.toString(), binding);

    IdeCommand focus = transport.commands.get(1);
    assertEquals(IdeAction.FOCUS_TERMINAL, focus.getAction());
    assertEquals(BROWSER_SESSION.toString(), focus.getBrowserSessionId());
    assertEquals(7L, focus.getBindingEpoch());
    assertEquals(DAEMON_INSTANCE, focus.getExpectedDaemonInstanceId());

    EnvironmentIdeCloseViewDTO close = closeRequest(true);
    transport.replies.add(snapshot(7L));
    service.closeView(ENV, VIEW.toString(), close);

    IdeCommand closed = transport.commands.get(2);
    assertEquals(IdeAction.CLOSE_VIEW, closed.getAction());
    assertEquals(BROWSER_SESSION.toString(), closed.getBrowserSessionId());
    assertEquals(7L, closed.getBindingEpoch());
    assertEquals(DAEMON_INSTANCE, closed.getExpectedDaemonInstanceId());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void listViewsRevokesReplacedEpochTicket() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    assertEquals(1, tickets.pendingCount());
    transport.replies.add(snapshot(6L, summary(8L, CLAIM)));

    EnvironmentIdeViewsDTO views = service.listViews(ENV);

    assertEquals(8L, views.getViews().get(0).getBindingEpoch());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void changeDirectoryRejectsOldEpochAfterResume() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    transport.replies.add(snapshot(6L, summary(8L, CLAIM)));

    service.listViews(ENV);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals("STALE_BINDING", error.reason());
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().getAction());
  }

  @Test
  void daemonGenerationReplacementInvalidatesCachedBindings() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    transport.replaceCurrent(new DaemonConnectionIdentity(ENV, REPLACEMENT_INSTANCE));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));

    assertEquals("VIEW_GONE", error.reason());
    assertEquals(List.of(IdeAction.OPEN_VIEW), actions());
  }

  @Test
  void lateSnapshotFromReplacedInstanceIsRejectedWithoutReplacingCurrentCache() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    DaemonConnectionIdentity replacement = new DaemonConnectionIdentity(ENV, REPLACEMENT_INSTANCE);
    transport.replaceCurrent(replacement);
    transport.replyAs(new DaemonConnectionIdentity(ENV, DAEMON_INSTANCE));
    transport.replies.add(snapshot(6L, summary(7L, CLAIM)));

    DomainException error = assertThrows(DomainException.class, () -> service.listViews(ENV));

    assertEquals("STALE_BINDING", error.reason());
    assertTrue(snapshotIfPresent().isEmpty());
  }

  @Test
  void changeDirectoryRejectsReplyWhenBindingEpochChangedBeforeCacheWrite() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    EnvironmentIdeChangeDirectoryDTO change = new EnvironmentIdeChangeDirectoryDTO();
    change.setBrowserSessionId(BROWSER_SESSION.toString());
    change.setBindingEpoch(7L);
    change.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    change.setDirectory("/next");
    transport.replies.add(snapshot(6L, summary(8L, CLAIM, "/daemon/next")));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), change));

    assertEquals("STALE_BINDING", error.reason());
    IdeViewSnapshotStore.Snapshot current = snapshot();
    assertEquals(5L, current.revision());
    assertEquals(7L, current.find(VIEW).orElseThrow().bindingEpoch());
  }

  @Test
  void openOutcomeUnknownAndReconciliationTimeoutRemainsUnknownWithoutReplay() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    transport.failHook = () -> transport.failNext.set(IdeAction.LIST_VIEWS);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("IDE_OUTCOME_UNKNOWN", error.reason());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
    assertEquals(2, transport.abandoned.get());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void terminalOutcomeUnknownIsNotReplayed() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    transport.failNext.set(IdeAction.FOCUS_TERMINAL);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));

    assertEquals("IDE_OUTCOME_UNKNOWN", error.reason());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.FOCUS_TERMINAL), actions());
  }

  /** 普通关闭等待用户确认，期间不得被命令 timeout 打断；只有显式 discard 才走有界等待。 */
  @Test
  void normalCloseWaitsForReplyWhileOtherCommandsRemainBounded() throws Exception {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));

    transport.failNext.set(IdeAction.FOCUS_TERMINAL);
    assertThrows(
        DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));

    transport.failNext.set(IdeAction.CLOSE_VIEW);
    assertThrows(
        DomainException.class, () -> service.closeView(ENV, VIEW.toString(), closeRequest(true)));

    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<IdeResult> pendingReply = transport.pending(started);
    CompletableFuture<Void> waiting =
        CompletableFuture.runAsync(
            () -> service.closeView(ENV, VIEW.toString(), closeRequest(false)));
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> waiting.get(500, TimeUnit.MILLISECONDS));
      assertEquals(2, transport.abandoned.get());
      pendingReply.complete(snapshot(6L));
      waiting.get(2, TimeUnit.SECONDS);
      assertEquals(2, transport.abandoned.get());
    } finally {
      pendingReply.completeExceptionally(new IllegalStateException("test cleanup"));
    }
  }

  /** 无限等待仍可中断，并且必须撤销在途命令；中断属于结果未知而不是未发送。 */
  @Test
  void interruptingNormalCloseAbandonsPendingCommandWithOutcomeUnknown() throws Exception {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<IdeResult> pendingReply = transport.pending(started);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
    Thread worker =
        new Thread(
            () -> {
              try {
                service.closeView(ENV, VIEW.toString(), closeRequest(false));
              } catch (RuntimeException error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    worker.start();
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS));
      worker.interrupt();
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertTrue(
          failure.get() instanceof DomainException error
              && "IDE_OUTCOME_UNKNOWN".equals(error.reason()));
      assertTrue(interrupted.get());
      assertEquals(1, transport.abandoned.get());
    } finally {
      worker.interrupt();
      pendingReply.completeExceptionally(new IllegalStateException("test cleanup"));
    }
  }

  @Test
  void cancelledCloseKeepsBindingAndReason() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    EnvironmentIdeCloseViewDTO close = closeRequest(false);
    transport.replies.add(
        IdeResult.newBuilder().setOk(false).setCode("CANCELLED").setMessage("cancelled").build());

    DomainException error =
        assertThrows(DomainException.class, () -> service.closeView(ENV, VIEW.toString(), close));

    assertEquals("IDE_CLOSE_CANCELLED", error.reason());
    assertTrue(snapshot().find(VIEW).isPresent());
  }

  @Test
  void daemonNotReadyBeforeSendIsDistinctFromOutcomeUnknown() {
    transport.ready = false;

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("DAEMON_NOT_READY", error.reason());
    assertTrue(transport.commands.isEmpty());
  }

  @Test
  void openViewFailsClosedWithoutExactBrowserIntent() {
    browserUse.matched = false;

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("STALE_BINDING", error.reason());
    assertTrue(transport.commands.isEmpty());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void missingIntentDoesNotDeleteExistingViewOrDispatchWriteCommand() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    int before = transport.commands.size();
    assertTrue(snapshot().find(VIEW).isPresent());
    assertEquals(1, tickets.pendingCount());

    browserUse.matched = false;
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.closeView(ENV, VIEW.toString(), closeRequest(false)));

    assertEquals("STALE_BINDING", error.reason());
    assertEquals(before, transport.commands.size());
    assertTrue(snapshot().find(VIEW).isPresent());
    assertEquals(1, tickets.pendingCount());
  }

  @Test
  void lateOpenReplyFromReplacedInstanceIsNotStoredOrTicketIssued() {
    transport.replies.add(openSnapshot(5L, 7L));
    transport.replyAs(new DaemonConnectionIdentity(ENV, OLD_INSTANCE));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("STALE_BINDING", error.reason());
    assertEquals(0, tickets.pendingCount());
    assertTrue(snapshotIfPresent().isEmpty());
  }

  @Test
  void reconcileOpenAfterInstanceReplacementReturnsViewGone() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    transport.failHook =
        () -> transport.replaceCurrent(new DaemonConnectionIdentity(ENV, NEW_INSTANCE));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.openView(ENV, openRequest("create", 0L, null)));

    assertEquals("VIEW_GONE", error.reason());
    assertEquals(List.of(IdeAction.OPEN_VIEW), actions());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void closeViewGoneRevokesExactBindingTicket() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    transport.replies.add(
        IdeResult.newBuilder().setOk(false).setCode("VIEW_GONE").setMessage("gone").build());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.closeView(ENV, VIEW.toString(), closeRequest(false)));

    assertEquals("VIEW_GONE", error.reason());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void changeDirectoryUnknownOutcomeIsNotReplayedOrAccepted() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    EnvironmentIdeChangeDirectoryDTO change = new EnvironmentIdeChangeDirectoryDTO();
    change.setBrowserSessionId(BROWSER_SESSION.toString());
    change.setBindingEpoch(7L);
    change.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    change.setDirectory("/next");
    transport.failNext.set(IdeAction.CHANGE_DIRECTORY);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), change));

    assertEquals("IDE_OUTCOME_UNKNOWN", error.reason());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.CHANGE_DIRECTORY), actions());
    assertEquals("/daemon/dir", snapshot().find(VIEW).orElseThrow().directory());
  }

  @Test
  void changeDirectoryRejectsDifferentBindingBeforeCacheWrite() {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    EnvironmentIdeChangeDirectoryDTO change = new EnvironmentIdeChangeDirectoryDTO();
    change.setBrowserSessionId(BROWSER_SESSION.toString());
    change.setBindingEpoch(7L);
    change.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    change.setDirectory("/next");
    UUID otherClaim = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    transport.replies.add(snapshot(6L, summary(7L, otherClaim, "/daemon/evil")));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), change));

    assertEquals("STALE_BINDING", error.reason());
    IdeViewSnapshotStore.Snapshot current = snapshot();
    assertEquals(5L, current.revision());
    assertEquals(CLAIM, current.find(VIEW).orElseThrow().claimId());
  }

  /** Rev2 的清理在 Rev3 接受并签发新票据之后才继续执行时，只能撤销旧精确 epoch。 */
  @Test
  void lateCleanupFromOlderRevisionCannotRevokeNewerEpochTicket() throws Exception {
    BlockingTicketService blockingTickets = new BlockingTicketService();
    service =
        new EnvironmentIdeServiceImpl(
            transport,
            blockingTickets,
            browserUse,
            snapshots,
            Duration.ofMillis(150),
            ownerQueryService,
            sessionBlobRefManager,
            blobRepository,
            blobContentService);
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, DAEMON_INSTANCE);

    transport.replies.add(snapshot(1L, summary(1L, CLAIM)));
    service.listViews(ENV);

    blockingTickets.blockEpoch = 1L;
    transport.replies.add(snapshot(2L, summary(2L, CLAIM)));
    Thread lateRev2 = new Thread(() -> service.listViews(ENV));
    lateRev2.start();
    assertTrue(
        blockingTickets.blocked.await(2, TimeUnit.SECONDS), "Rev2 cleanup must be in flight");

    transport.replies.add(snapshot(3L, summary(3L, CLAIM)));
    service.listViews(ENV);
    String newTicket = blockingTickets.issue(identity, VIEW.toString(), 3L, "/daemon/ws");

    blockingTickets.release.countDown();
    lateRev2.join(2000);
    assertFalse(lateRev2.isAlive());
    assertTrue(
        blockingTickets.consume(newTicket).isPresent(),
        "late Rev2 cleanup must not revoke the accepted Rev3 ticket");
  }

  private void prepareAttachment(byte[] bytes, String mediaType) {
    transport.replies.add(openSnapshot(5L, 7L));
    service.openView(ENV, openRequest("create", 0L, null));
    when(sessionBlobRefManager.contains(ATTACHMENT_SESSION_ID, ATTACHMENT_ID)).thenReturn(true);
    StorageBlob blob = new StorageBlob();
    blob.setId(ATTACHMENT_ID);
    blob.setMediaType(mediaType);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobRepository.getById(ATTACHMENT_ID)).thenReturn(blob);
    doAnswer(
            invocation -> {
              Function<InputStream, Object> reader = invocation.getArgument(2);
              return reader.apply(new ByteArrayInputStream(bytes));
            })
        .when(blobContentService)
        .withBlobStream(eq(ATTACHMENT_ID), any(), any());
    transport.replies.add(snapshot(6L, summary(7L, CLAIM)));
  }

  private List<IdeAction> actions() {
    return transport.commands.stream().map(IdeCommand::getAction).toList();
  }

  private static EnvironmentIdeOpenViewDTO openRequest(
      String mode, long expectedEpoch, String directory) {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW.toString());
    request.setBrowserSessionId(BROWSER_SESSION.toString());
    request.setEventNodeId(EVENT_NODE.toString());
    request.setConnectionId(CONNECTION.toString());
    request.setClaimId(CLAIM.toString());
    request.setMode(EnvironmentIdeOpenMode.fromWireValue(mode));
    request.setExpectedBindingEpoch(expectedEpoch);
    request.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    request.setDirectory(directory);
    return request;
  }

  private static EnvironmentIdeReadViewDTO readRequest(UUID browserSessionId) {
    EnvironmentIdeReadViewDTO request = new EnvironmentIdeReadViewDTO();
    request.setBrowserSessionId(browserSessionId.toString());
    request.setEventNodeId(EVENT_NODE.toString());
    request.setConnectionId(CONNECTION.toString());
    request.setClaimId(CLAIM.toString());
    request.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    return request;
  }

  private static EnvironmentIdeViewBindingDTO binding(long epoch) {
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setBrowserSessionId(BROWSER_SESSION.toString());
    binding.setBindingEpoch(epoch);
    binding.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    return binding;
  }

  private static EnvironmentIdeCloseViewDTO closeRequest(boolean discard) {
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    close.setBrowserSessionId(BROWSER_SESSION.toString());
    close.setBindingEpoch(7L);
    close.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    close.setDiscard(discard);
    return close;
  }

  private static IdeResult openSnapshot(long revision, long epoch) {
    return snapshot(revision, summary(epoch, CLAIM));
  }

  /** 测试按当前 READY 身份读取权威快照，避免为测试保留生产无用的入口。 */
  private IdeViewSnapshotStore.Snapshot snapshot() {
    return snapshotIfPresent()
        .orElseThrow(() -> new AssertionError("expected an accepted IDE snapshot"));
  }

  private Optional<IdeViewSnapshotStore.Snapshot> snapshotIfPresent() {
    return transport.connectionIdentity(ENV).flatMap(snapshots::snapshot);
  }

  private static IdeResult snapshot(long revision, IdeViewSummary... views) {
    return IdeResult.newBuilder()
        .setOk(true)
        .setRevision(revision)
        .addAllViews(List.of(views))
        .build();
  }

  private static IdeViewSummary summary(long epoch, UUID claimId) {
    return summary(epoch, claimId, "/daemon/dir");
  }

  private static IdeViewSummary summary(long epoch, UUID claimId, String directory) {
    return IdeViewSummary.newBuilder()
        .setViewId(VIEW.toString())
        .setBrowserSessionId(BROWSER_SESSION.toString())
        .setEventNodeId(EVENT_NODE.toString())
        .setConnectionId(CONNECTION.toString())
        .setClaimId(claimId.toString())
        .setBindingEpoch(epoch)
        .setDirectory(directory)
        .setWorkspacePath("/daemon/ws")
        .setState(IdeViewState.OPEN)
        .build();
  }

  private record IntentQuery(
      UUID eventNodeId, UUID connectionId, UUID browserSessionId, UUID viewId, UUID claimId) {}

  private static final class FakeTransport extends IdeCommandTransport {

    private FakeTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    private final List<IdeCommand> commands = new CopyOnWriteArrayList<>();
    private final Deque<IdeResult> replies = new ArrayDeque<>();
    private final AtomicReference<IdeAction> failNext = new AtomicReference<>();
    private final AtomicInteger abandoned = new AtomicInteger();
    private volatile Runnable failHook;
    private volatile CompletableFuture<IdeResult> pendingReply;
    private volatile CountDownLatch pendingStarted;
    private volatile DaemonConnectionIdentity identity =
        new DaemonConnectionIdentity(ENV, DAEMON_INSTANCE);
    private volatile DaemonConnectionIdentity invocationIdentity;
    private volatile boolean ready = true;

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeResult> future;
      CompletableFuture<IdeResult> pending = pendingReply;
      if (pending != null) {
        pendingReply = null;
        future = pending;
        CountDownLatch started = pendingStarted;
        if (started != null) {
          started.countDown();
        }
      } else {
        future = new CompletableFuture<>();
        IdeAction failAction = failNext.get();
        if (failAction == command.getAction() && failNext.compareAndSet(failAction, null)) {
          // 让该命令超时，模拟结果未知。
          Runnable hook = failHook;
          if (hook != null) {
            hook.run();
          }
        } else {
          synchronized (replies) {
            future.complete(replies.removeFirst());
          }
        }
      }
      DaemonConnectionIdentity bound = invocationIdentity == null ? identity : invocationIdentity;
      return new IdeRequest(bound, UUID.randomUUID(), future);
    }

    private void replaceCurrent(DaemonConnectionIdentity replacement) {
      identity = replacement;
    }

    private void replyAs(DaemonConnectionIdentity replyIdentity) {
      invocationIdentity = replyIdentity;
    }

    private CompletableFuture<IdeResult> pending(CountDownLatch started) {
      CompletableFuture<IdeResult> future = new CompletableFuture<>();
      pendingReply = future;
      pendingStarted = started;
      return future;
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return ready ? Optional.of(identity) : Optional.empty();
    }

    @Override
    public void sendIdeLease(
        EnvironmentId environmentId,
        DaemonConnectionIdentity expectedIdentity,
        DaemonIdeLease lease) {
      throw new AssertionError("control service must not send leases");
    }

    @Override
    public void abandonIde(UUID invocationId) {
      abandoned.incrementAndGet();
    }
  }

  private static final class FakeBrowserUse extends IdeBrowserUseGateway {

    private FakeBrowserUse() {
      super(mock(IdeBrowserUseClient.class));
    }

    private final List<IntentQuery> queries = new CopyOnWriteArrayList<>();
    private volatile boolean matched = true;

    @Override
    public boolean findExactIntent(
        UUID eventNodeId,
        UUID connectionId,
        UUID browserSessionId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId) {
      queries.add(new IntentQuery(eventNodeId, connectionId, browserSessionId, viewId, claimId));
      return matched;
    }

    @Override
    public Optional<Proofs> activeBoundProofs(
        UUID eventNodeId, UUID connectionId, UUID browserSessionId) {
      return Optional.empty();
    }
  }

  private static final class BlockingTicketService extends IdeBootstrapTicketService {

    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile long blockEpoch = -1;

    @Override
    public void revoke(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {
      if (bindingEpoch == blockEpoch) {
        blocked.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("ticket cleanup was not released");
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("ticket cleanup was interrupted", error);
        }
      }
      super.revoke(identity, viewId, bindingEpoch);
    }
  }
}
