package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import fun.fengwk.kkstudio.core.agent.runtime.engine.AgentRequestTask;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSessionStatus;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import fun.fengwk.kkstudio.core.agent.runtime.repo.AgentRequestTaskRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentBusyRecoveryCheckerImpl} 单元测试。
 *
 * @author fengwk
 */
public class AgentBusyRecoveryCheckerImplTest {

    @Test
    public void testCheckNowRecoversStaleBusySession() {
        InMemoryEventSessionRepository eventSessionRepository = new InMemoryEventSessionRepository();
        eventSessionRepository.session = busySession(LocalDateTime.now().minusMinutes(31));
        InMemoryAgentRequestTaskRepository taskRepository = new InMemoryAgentRequestTaskRepository(true);
        AgentBusyRecoveryProperties properties = new AgentBusyRecoveryProperties();
        properties.setBusyTimeout(Duration.ofMinutes(30));
        AtomicBoolean recovered = new AtomicBoolean(false);
        AtomicReference<String> reasonRef = new AtomicReference<>();

        AgentBusyRecoveryCheckerImpl checker = new AgentBusyRecoveryCheckerImpl(
            eventSessionRepository, taskRepository, properties, objectProvider((sessionId, reason) -> {
                recovered.set(true);
                reasonRef.set(reason);
                return true;
            }));

        try {
            assertTrue(checker.checkNow("s1", "manual recovery"));
            assertTrue(recovered.get());
            assertEquals("manual recovery", reasonRef.get());
        } finally {
            checker.destroy();
        }
    }

    @Test
    public void testCheckNowDoesNotRecoverActiveBusySession() {
        InMemoryEventSessionRepository eventSessionRepository = new InMemoryEventSessionRepository();
        eventSessionRepository.session = busySession(LocalDateTime.now());
        InMemoryAgentRequestTaskRepository taskRepository = new InMemoryAgentRequestTaskRepository(true);
        AgentBusyRecoveryProperties properties = new AgentBusyRecoveryProperties();
        properties.setBusyTimeout(Duration.ofMinutes(30));
        AtomicBoolean recovered = new AtomicBoolean(false);

        AgentBusyRecoveryCheckerImpl checker = new AgentBusyRecoveryCheckerImpl(
            eventSessionRepository, taskRepository, properties, objectProvider((sessionId, reason) -> {
                recovered.set(true);
                return true;
            }));

        try {
            assertFalse(checker.checkNow("s1", "manual recovery"));
            assertFalse(recovered.get());
        } finally {
            checker.destroy();
        }
    }

    private static EventSession busySession(LocalDateTime lastEventAt) {
        return EventSession.builder()
            .sessionId("s1")
            .status(EventSessionStatus.busy)
            .headEventId("e1")
            .runningTurnId("t1")
            .runningSince(lastEventAt)
            .lastEventAt(lastEventAt)
            .build();
    }

    private static ObjectProvider<AgentBusyRecoveryHandler> objectProvider(AgentBusyRecoveryHandler handler) {
        return new ObjectProvider<>() {

            @Override
            public AgentBusyRecoveryHandler getObject(Object... args) {
                return handler;
            }

            @Override
            public AgentBusyRecoveryHandler getIfAvailable() {
                return handler;
            }

            @Override
            public AgentBusyRecoveryHandler getObject() {
                return handler;
            }
        };
    }

    private static class InMemoryEventSessionRepository implements EventSessionRepository {

        private EventSession session;

        @Override
        public EventSession newSession(String headEventId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventSession get(String sessionId) {
            return session;
        }

        @Override
        public boolean casHeadEventId(String sessionId, String oldHeadEventId, String newHeadEventId,
                                      EventSessionStatus newStatus, String runningTurnId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateSessionConfig(String sessionId, ProviderConfig providerConfig,
                                        ModelRequestConfig modelRequestConfig, Map<String, Object> parameters,
                                        String systemPrompt) {
            throw new UnsupportedOperationException();
        }
    }

    private static class InMemoryAgentRequestTaskRepository implements AgentRequestTaskRepository {

        private final boolean pending;

        private InMemoryAgentRequestTaskRepository(boolean pending) {
            this.pending = pending;
        }

        @Override
        public String generateTaskId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRequestTask add(AgentRequestTask agentRequestTask) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentRequestTask> listPendingForUpdate(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean consumeAll(List<String> taskIdList, String turnId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPending(String sessionId) {
            return pending;
        }
    }

}
