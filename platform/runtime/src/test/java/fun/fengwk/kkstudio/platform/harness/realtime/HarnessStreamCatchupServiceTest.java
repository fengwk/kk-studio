package fun.fengwk.kkstudio.platform.harness.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationStatus;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodePresence;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流式追帧服务：只有 durable RUNNING 且目标 Thread 快照与 attempt 精确匹配时才触碰执行状态；本地优先、单节点零 NOTIFY，跨节点只发布一次。
 *
 * <p>所有测试都验证失败/不匹配路径完全 no-op；数据库查询或发布异常向上传播，由 HTTP 边界显式呈现，不伪造 202。
 */
class HarnessStreamCatchupServiceTest {

  private static final UUID LOCAL = new UUID(0L, 900L);
  private static final UUID REMOTE = new UUID(0L, 901L);
  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID INVOCATION = new UUID(0L, 2L);
  private static final UUID OTHER_THREAD = new UUID(0L, 3L);
  private static final UUID OTHER_INVOCATION = new UUID(0L, 4L);
  private static final int ATTEMPT = 3;

  private HarnessRuntime runtime;
  private ModelProcessor modelProcessor;
  private ClusterNodePresence clusterNodePresence;
  private JdbcTemplate jdbcTemplate;
  private ThreadSnapshot snapshot;
  private GenerationProjection generation;
  private HarnessStreamCatchupNotificationCodec codec;
  private HarnessStreamCatchupService service;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    modelProcessor = mock(ModelProcessor.class);
    clusterNodePresence = mock(ClusterNodePresence.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    snapshot = mock(ThreadSnapshot.class);
    generation = mock(GenerationProjection.class);
    codec = new HarnessStreamCatchupNotificationCodec();
    when(runtime.getThreadSnapshot(THREAD)).thenReturn(snapshot);
    when(snapshot.generation()).thenReturn(generation);
    when(generation.generationId()).thenReturn(INVOCATION);
    when(generation.attempt()).thenReturn(ATTEMPT);
    when(generation.status()).thenReturn(GenerationStatus.RUNNING);
    service =
        new HarnessStreamCatchupService(
            runtime, modelProcessor, clusterNodePresence, jdbcTemplate, LOCAL, codec);
  }

  @Test
  void localOwnerCheckpointMeansNoClusterLookupAndNoNotify() throws Exception {
    when(modelProcessor.requestCheckpoint(INVOCATION, ATTEMPT)).thenReturn(true);

    service.catchUp(THREAD, INVOCATION, ATTEMPT);

    verify(modelProcessor).requestCheckpoint(INVOCATION, ATTEMPT);
    verifyNoInteractions(clusterNodePresence);
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void missingLocalOwnerWithPeerPublishesExactlyOnce() throws Exception {
    when(modelProcessor.requestCheckpoint(INVOCATION, ATTEMPT)).thenReturn(false);
    when(clusterNodePresence.hasOtherActiveNodes()).thenReturn(true);

    service.catchUp(THREAD, INVOCATION, ATTEMPT);

    HarnessStreamCatchupNotificationCodec.Envelope envelope =
        capturePublishedEnvelope(jdbcTemplate, codec);
    assertEquals(LOCAL, envelope.originNodeId());
    assertEquals(THREAD, envelope.threadId());
    assertEquals(INVOCATION, envelope.invocationId());
    assertEquals(ATTEMPT, envelope.attempt());
    verify(clusterNodePresence, times(1)).hasOtherActiveNodes();
    verify(jdbcTemplate, times(1))
        .execute(eq(HarnessStreamCatchupService.NOTIFY_SQL), any(PreparedStatementCallback.class));
  }

  @Test
  void singleNodeWithoutLocalOwnerDoesNotNotify() throws Exception {
    when(modelProcessor.requestCheckpoint(INVOCATION, ATTEMPT)).thenReturn(false);
    when(clusterNodePresence.hasOtherActiveNodes()).thenReturn(false);

    service.catchUp(THREAD, INVOCATION, ATTEMPT);

    verify(clusterNodePresence).hasOtherActiveNodes();
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void staleAttemptOrMismatchedInvocationIsBestEffortNoOp() {
    service.catchUp(THREAD, INVOCATION, ATTEMPT - 1);
    service.catchUp(THREAD, OTHER_INVOCATION, ATTEMPT);
    when(generation.generationId()).thenReturn(OTHER_INVOCATION);
    service.catchUp(THREAD, INVOCATION, ATTEMPT);

    verifyNoInteractions(modelProcessor);
    verifyNoInteractions(clusterNodePresence);
    verifyNoInteractions(jdbcTemplate);
  }

  /** 跨 Thread 的 generation 必须由 ThreadSnapshot 的 ownership 约束拒绝，不能靠 catch-up 服务在运行时猜测归属。 */
  @Test
  void foreignGenerationOwnershipIsRejectedBeforeCatchup() {
    UUID foreignThreadId = OTHER_THREAD;
    UUID foreignTurnId = new UUID(0L, 10L);
    UUID ownerTurnId = new UUID(0L, 11L);
    ThreadState foreignThread = mock(ThreadState.class);
    when(foreignThread.id()).thenReturn(foreignThreadId);
    Entry foreignTurn = mock(Entry.class);
    when(foreignTurn.id()).thenReturn(foreignTurnId);
    when(foreignTurn.payload())
        .thenReturn(
            new TurnStartPayload(
                TurnStartReason.INPUT,
                new BranchSettings("agent", new ModelSelection("model", "v1"), null),
                foreignThreadId));
    EntryPath foreignPath = mock(EntryPath.class);
    when(foreignPath.openTurnStart()).thenReturn(Optional.of(foreignTurn));
    when(foreignPath.entries()).thenReturn(List.of(foreignTurn));
    GenerationProjection foreignGeneration =
        new GenerationProjection(
            INVOCATION,
            ownerTurnId,
            ownerTurnId,
            GenerationStatus.RUNNING,
            ATTEMPT,
            0L,
            List.of(),
            null,
            null,
            Instant.EPOCH,
            Instant.EPOCH,
            List.of());

    assertThrows(
        IllegalStateException.class,
        () ->
            new ThreadSnapshot(
                foreignThread,
                foreignPath,
                List.of(),
                foreignGeneration,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
  }

  @Test
  void noModelContextIsBestEffortNoOp() {
    when(snapshot.generation()).thenReturn(null);

    service.catchUp(THREAD, INVOCATION, ATTEMPT);

    verifyNoInteractions(modelProcessor);
    verifyNoInteractions(clusterNodePresence);
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void everyNonRunningGenerationStatusIsBestEffortNoOp() {
    // 只有 RUNNING 才允许追帧；READY、等待与全部终态都不得触达 owner、presence 或 NOTIFY。
    for (GenerationStatus status : GenerationStatus.values()) {
      if (status == GenerationStatus.RUNNING) {
        continue;
      }
      when(generation.status()).thenReturn(status);
      service.catchUp(THREAD, INVOCATION, ATTEMPT);
    }

    verifyNoInteractions(modelProcessor);
    verifyNoInteractions(clusterNodePresence);
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void remoteNotificationReFencesExactAttemptWithoutSnapshotOrRebroadcast() throws Exception {
    String payload =
        codec.encode(
            new HarnessStreamCatchupNotificationCodec.Envelope(
                REMOTE, THREAD, INVOCATION, ATTEMPT));

    service.onNotification(payload);

    verify(modelProcessor).requestCheckpoint(INVOCATION, ATTEMPT);
    verify(runtime, never()).getThreadSnapshot(any());
    verifyNoInteractions(clusterNodePresence);
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void ownOriginNotificationIsIgnoredAndMalformedPayloadIsDropped() throws Exception {
    service.onNotification(
        codec.encode(
            new HarnessStreamCatchupNotificationCodec.Envelope(
                LOCAL, THREAD, INVOCATION, ATTEMPT)));
    service.onNotification("{\"originNodeId\":\"broken\"}");
    service.onNotification("not-json");

    verifyNoInteractions(modelProcessor);
    verifyNoInteractions(runtime);
    verifyNoInteractions(clusterNodePresence);
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void malformedNotificationLogsOnlyErrorTypeWithoutPayloadOrThrowable() {
    ListAppender<ILoggingEvent> appender = attachLogCapture();
    try {
      service.onNotification("{\"originNodeId\":\"secret-payload-marker\"");
    } finally {
      detachLogCapture(appender);
    }

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertNull(event.getThrowableProxy());
    assertFalse(event.getFormattedMessage().contains("secret-payload-marker"));
    assertTrue(event.getFormattedMessage().contains(IllegalArgumentException.class.getName()));
  }

  @Test
  void clusterLookupFailurePropagatesInsteadOfPretendingAccepted() {
    when(modelProcessor.requestCheckpoint(INVOCATION, ATTEMPT)).thenReturn(false);
    when(clusterNodePresence.hasOtherActiveNodes())
        .thenThrow(new DataAccessResourceFailureException("cluster lookup failed"));

    assertThrows(
        DataAccessResourceFailureException.class,
        () -> service.catchUp(THREAD, INVOCATION, ATTEMPT));

    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void notifyFailurePropagatesInsteadOfPretendingAccepted() {
    when(modelProcessor.requestCheckpoint(INVOCATION, ATTEMPT)).thenReturn(false);
    when(clusterNodePresence.hasOtherActiveNodes()).thenReturn(true);
    doThrow(new DataAccessResourceFailureException("notify failed"))
        .when(jdbcTemplate)
        .execute(eq(HarnessStreamCatchupService.NOTIFY_SQL), any(PreparedStatementCallback.class));

    assertThrows(
        DataAccessResourceFailureException.class,
        () -> service.catchUp(THREAD, INVOCATION, ATTEMPT));
  }

  private static ListAppender<ILoggingEvent> attachLogCapture() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(HarnessStreamCatchupService.class)).addAppender(appender);
    return appender;
  }

  private static void detachLogCapture(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(HarnessStreamCatchupService.class)).detachAppender(appender);
    appender.stop();
  }

  private static HarnessStreamCatchupNotificationCodec.Envelope capturePublishedEnvelope(
      JdbcTemplate jdbcTemplate, HarnessStreamCatchupNotificationCodec codec) throws Exception {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<PreparedStatementCallback<Void>> callback =
        ArgumentCaptor.forClass(PreparedStatementCallback.class);
    verify(jdbcTemplate).execute(eq(HarnessStreamCatchupService.NOTIFY_SQL), callback.capture());
    PreparedStatement statement = mock(PreparedStatement.class);
    callback.getValue().doInPreparedStatement(statement);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(statement).setString(1, HarnessStreamCatchupService.CHANNEL);
    verify(statement).setString(eq(2), payload.capture());
    verify(statement).execute();
    return codec.decode(payload.getValue());
  }
}
