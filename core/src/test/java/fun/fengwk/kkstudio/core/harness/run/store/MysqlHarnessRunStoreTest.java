package fun.fengwk.kkstudio.core.harness.run.store;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ConcurrentModificationException;
import org.junit.jupiter.api.Test;

class MysqlHarnessRunStoreTest {
  private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

  /** 持续 CAS 竞争必须有界退出，不能让单次 worker cadence 无限占用线程。 */
  @Test
  void boundsClaimContentionRetries() {
    HarnessRunMapper runMapper = mock(HarnessRunMapper.class);
    HarnessRunDO candidate = new HarnessRunDO();
    candidate.setId(1L);
    when(runMapper.findClaimCandidate(any(LocalDateTime.class))).thenReturn(candidate);
    when(runMapper.claim(
            anyLong(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(0);
    MysqlHarnessRunStore store = store(runMapper);

    assertTrue(store.claimDue("worker", NOW, Duration.ofSeconds(30)).isEmpty());

    verify(runMapper, times(64)).findClaimCandidate(any(LocalDateTime.class));
    verify(runMapper, times(64))
        .claim(anyLong(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
  }

  /** 数据库读取异常必须首轮向上抛出，不得在故障期间重试成 CPU busy loop。 */
  @Test
  void propagatesClaimDatabaseFailureWithoutRetrying() {
    HarnessRunMapper runMapper = mock(HarnessRunMapper.class);
    IllegalStateException failure = new IllegalStateException("database unavailable");
    when(runMapper.findClaimCandidate(any(LocalDateTime.class))).thenThrow(failure);
    MysqlHarnessRunStore store = store(runMapper);

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> store.claimDue("worker", NOW, Duration.ofSeconds(30))));

    verify(runMapper).findClaimCandidate(any(LocalDateTime.class));
    verify(runMapper, never())
        .claim(anyLong(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
  }

  /** event insert 未生效时必须失败，使外层事务回滚已分配的 sequence。 */
  @Test
  void rejectsUnpersistedRunEvent() {
    HarnessRunMapper runMapper = mock(HarnessRunMapper.class);
    HarnessRunEventMapper eventMapper = mock(HarnessRunEventMapper.class);
    HarnessSessionMapper sessionMapper = mock(HarnessSessionMapper.class);
    RunIdGenerator idGenerator = mock(RunIdGenerator.class);
    HarnessRunDO run = new HarnessRunDO();
    run.setId(1L);
    run.setSessionId(9L);
    run.setEventSequence(0L);
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(9L);
    session.setRootSessionId(9L);
    when(runMapper.findForUpdate(1L)).thenReturn(run);
    when(sessionMapper.findForUpdate(9L)).thenReturn(session);
    when(runMapper.updateEventSequence(anyLong(), anyLong(), anyLong(), any(LocalDateTime.class)))
        .thenReturn(1);
    when(idGenerator.newRunEventId()).thenReturn(2L);
    when(eventMapper.insert(any())).thenReturn(0);
    MysqlHarnessRunStore store =
        new MysqlHarnessRunStore(
            runMapper,
            eventMapper,
            new HarnessRunEventWriter(runMapper, eventMapper, sessionMapper, idGenerator));

    assertThrows(
        ConcurrentModificationException.class,
        () -> store.append(1L, RunEventType.RUN_STARTED, "{\"schemaVersion\":1}", NOW));
  }

  private static MysqlHarnessRunStore store(HarnessRunMapper runMapper) {
    return new MysqlHarnessRunStore(
        runMapper, mock(HarnessRunEventMapper.class), mock(HarnessRunEventWriter.class));
  }
}
