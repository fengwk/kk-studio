package fun.fengwk.kkstudio.platform.orchestration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Runtime tree/session 深删除与 Session blob 引用释放的协调契约。 */
class SessionDeletionOrchestratorTest {

  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private SessionBlobRefManager refManager;
  private SessionDeletionOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    refManager = mock(SessionBlobRefManager.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    service = new SessionDeletionOrchestrator(runtimes, refManager);
  }

  /** Session 深删仍通过 Runtime 删除接口，不在协调器中增加 owner 路由。 */
  @Test
  void deleteSessionUsesRuntimeDeletionCallbacks() {
    UUID sessionId = UUID.randomUUID();
    service.deleteSession(sessionId, ignored -> {});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<List<UUID>>> relations =
        ArgumentCaptor.forClass(Consumer.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<List<UUID>>> refs =
        ArgumentCaptor.forClass(Consumer.class);
    verify(runtime).deleteSessions(eq(List.of(sessionId)), relations.capture(), refs.capture());
    relations.getValue().accept(List.of(sessionId));
    refs.getValue().accept(List.of(sessionId));
    verify(refManager).listBlobIds(sessionId);
  }
}
