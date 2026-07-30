package fun.fengwk.kkstudio.core.harness.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.harness.model.worker.ModelInvocationDO;
import fun.fengwk.kkstudio.core.harness.model.worker.ModelInvocationMapper;
import fun.fengwk.kkstudio.core.harness.observability.service.impl.HarnessObservabilityDtoConverter;
import fun.fengwk.kkstudio.core.harness.observability.service.impl.HarnessObservabilityQueryServiceImpl;
import fun.fengwk.kkstudio.core.harness.session.service.impl.HarnessSessionQueryServiceImpl;
import fun.fengwk.kkstudio.core.harness.thread.service.impl.HarnessThreadQueryServiceImpl;
import fun.fengwk.kkstudio.core.harness.tool.worker.PostgresqlToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 核心 query service/store 的边界与投影覆盖。 */
class HarnessQueryServicesUnitTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private PostgresqlHarnessQueryMapper queryMapper;
  private HarnessQueryDtoConverter converter;
  private HarnessSessionQueryServiceImpl sessionQuery;
  private HarnessThreadQueryServiceImpl threadQuery;
  private HarnessObservabilityQueryServiceImpl observabilityQuery;
  private ModelInvocationMapper modelInvocationMapper;
  private InteractionMapper interactionMapper;

  @BeforeEach
  void setUp() {
    queryMapper = mock(PostgresqlHarnessQueryMapper.class);
    converter = new HarnessQueryDtoConverter();
    modelInvocationMapper = mock(ModelInvocationMapper.class);
    interactionMapper = mock(InteractionMapper.class);
    sessionQuery = new HarnessSessionQueryServiceImpl(queryMapper, converter);
    threadQuery = new HarnessThreadQueryServiceImpl(queryMapper, converter, CLOCK);
    observabilityQuery =
        new HarnessObservabilityQueryServiceImpl(
            queryMapper,
            mock(PostgresqlToolInvocationMapper.class),
            modelInvocationMapper,
            interactionMapper,
            mock(ArtifactStore.class),
            converter,
            new HarnessObservabilityDtoConverter());
  }

  @Test
  void sessionQueryLoadsSessionsEntriesAndRejectsMissing() {
    HarnessQueryRow session = session(1L, "root");
    when(queryMapper.findSession(1L)).thenReturn(session);
    when(queryMapper.listSessions()).thenReturn(List.of(session));
    when(queryMapper.listEntriesBySession(1L)).thenReturn(List.of(entry(10L, 1L, null, "ROOT")));

    HarnessSessionDTO dto = sessionQuery.getSession("1");
    assertEquals("1", dto.getSessionId());
    assertEquals(1, sessionQuery.listSessions().size());
    assertEquals(1, sessionQuery.listEntries("1").size());
    assertThrows(IllegalArgumentException.class, () -> sessionQuery.getSession("2"));
    assertThrows(IllegalArgumentException.class, () -> sessionQuery.listEntries("abc"));
  }

  @Test
  void threadQueryDerivesStatusAndListsThreads() {
    HarnessQueryRow thread = thread(21L, 1L, 10L, true, false);
    thread.setHasQueuedInput(true);
    when(queryMapper.findThreadView(21L)).thenReturn(thread);
    when(queryMapper.findSession(1L)).thenReturn(session(1L, "root"));
    when(queryMapper.listAllThreadViews()).thenReturn(List.of(thread));
    HarnessThreadDTO dto = threadQuery.getThread("21");
    assertEquals("WAITING", dto.getStatus());
    assertEquals(1, threadQuery.listAll().size());
    assertThrows(IllegalArgumentException.class, () -> threadQuery.getThread("999"));
  }

  @Test
  void unboundThreadIsVisible() {
    HarnessQueryRow unbound = thread(31L, null, null, false, false);
    when(queryMapper.findThreadView(31L)).thenReturn(unbound);
    when(queryMapper.listAllThreadViews()).thenReturn(List.of(unbound));

    HarnessThreadDTO dto = threadQuery.getThread("31");
    assertEquals("UNBOUND", dto.getStatus());
    assertNull(dto.getSessionId());
    assertNull(dto.getHeadEntryId());
    assertEquals(1, threadQuery.listAll().size());
  }

  @Test
  void observabilityProjectsModelInteractionsAndRejectsUnknownInputs() {
    when(queryMapper.findThreadView(21L)).thenReturn(thread(21L, 1L, 10L, false, false));
    ModelInvocationDO model = new ModelInvocationDO();
    model.setId(7L);
    model.setThreadId(21L);
    model.setSourceHeadEntryId(10L);
    model.setExecutionEpoch(0L);
    model.setStatus("QUEUED");
    model.setAttempt(1);
    when(modelInvocationMapper.listByThread(21L)).thenReturn(List.of(model));
    InteractionDO open = new InteractionDO();
    open.setId(8L);
    open.setOwnerKind("THREAD");
    open.setOwnerId(21L);
    open.setHandlerType("ASK");
    open.setRequestJson("{}");
    open.setStatus("OPEN");
    open.setVersion(0L);
    open.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    when(interactionMapper.listOpenByThread(21L)).thenReturn(List.of(open));

    assertEquals(1, observabilityQuery.listModelInvocations("21").size());
    assertEquals(1, observabilityQuery.listOpenInteractions("21").size());
    assertThrows(
        IllegalArgumentException.class, () -> observabilityQuery.listModelInvocations("9"));
    assertThrows(
        IllegalArgumentException.class, () -> observabilityQuery.getArtifact("not-a-number"));
    assertThrows(IllegalArgumentException.class, () -> observabilityQuery.getToolInvocation("0"));
  }

  @Test
  void converterHandlesNulls() {
    assertNull(converter.toSession(null));
    assertNull(converter.toEntry(null));
    assertNull(converter.toThread(null, NOW));
    assertNull(converter.toInput(null));
    assertNull(converter.toInteraction(null));
    assertNull(converter.toModelInvocation(null));
    HarnessQueryRow session = session(2L, "simple");
    HarnessSessionDTO dto = converter.toSession(session);
    assertEquals("2", dto.getSessionId());
    assertEquals("simple", dto.getTitle());
    assertNotNull(dto.getCreateTime());
    assertNotNull(dto.getUpdateTime());
  }

  private static HarnessQueryRow session(long id, String title) {
    HarnessQueryRow row = new HarnessQueryRow();
    row.setId(id);
    row.setTitle(title);
    row.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    row.setUpdatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return row;
  }

  private static HarnessQueryRow thread(
      long id, Long sessionId, Long head, boolean queued, boolean runnable) {
    HarnessQueryRow row = new HarnessQueryRow();
    row.setId(id);
    row.setSessionId(sessionId);
    row.setHeadEntryId(head);
    row.setInputSequence(0L);
    row.setRunnable(runnable);
    row.setExecutionEpoch(0L);
    row.setHasQueuedInput(queued);
    row.setHasActiveModel(false);
    row.setHasActiveTool(false);
    row.setHasOpenInteraction(false);
    row.setSessionTitle("title");
    row.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    row.setUpdatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return row;
  }

  private static HarnessQueryRow entry(long id, long sessionId, Long parent, String type) {
    HarnessQueryRow row = new HarnessQueryRow();
    row.setId(id);
    row.setSessionId(sessionId);
    row.setParentEntryId(parent);
    row.setEntryType(type);
    row.setPayloadJson("{}");
    row.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return row;
  }

  private static HarnessQueryRow input(
      long id, long threadId, long sequence, String type, String status) {
    HarnessQueryRow row = new HarnessQueryRow();
    row.setId(id);
    row.setThreadId(threadId);
    row.setSequence(sequence);
    row.setInputType(type);
    row.setPayloadJson("{\"x\":1}");
    row.setIdempotencyKey("k");
    row.setStatus(status);
    row.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return row;
  }
}
