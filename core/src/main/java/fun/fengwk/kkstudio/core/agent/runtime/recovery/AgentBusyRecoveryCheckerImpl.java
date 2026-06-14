package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSessionStatus;
import fun.fengwk.kkstudio.core.agent.runtime.repo.AgentRequestTaskRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本节点内存版 busy session stale 检查器。
 *
 * @author fengwk
 */
@Slf4j
@Component
@ConditionalOnBean({EventSessionRepository.class, AgentRequestTaskRepository.class})
public class AgentBusyRecoveryCheckerImpl implements AgentBusyRecoveryChecker, DisposableBean {

    private final EventSessionRepository eventSessionRepository;
    private final AgentRequestTaskRepository agentRequestTaskRepository;
    private final AgentBusyRecoveryProperties properties;
    private final ObjectProvider<AgentBusyRecoveryHandler> recoveryHandlerProvider;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, LocalDateTime> checkAtBySession = new ConcurrentHashMap<>();

    public AgentBusyRecoveryCheckerImpl(EventSessionRepository eventSessionRepository,
                                        AgentRequestTaskRepository agentRequestTaskRepository,
                                        AgentBusyRecoveryProperties properties,
                                        ObjectProvider<AgentBusyRecoveryHandler> recoveryHandlerProvider) {
        this.eventSessionRepository = Objects.requireNonNull(eventSessionRepository, "eventSessionRepository must not be null");
        this.agentRequestTaskRepository = Objects.requireNonNull(agentRequestTaskRepository, "agentRequestTaskRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.recoveryHandlerProvider = Objects.requireNonNull(recoveryHandlerProvider, "recoveryHandlerProvider must not be null");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-busy-recovery-checker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onSubmitQueued(String sessionId) {
        schedule(sessionId, false);
    }

    @Override
    public boolean checkNow(String sessionId, String reason) {
        return check(sessionId, null, true, reason);
    }

    @Override
    public void onPropertiesRefreshed() {
        checkAtBySession.keySet().forEach(sessionId -> schedule(sessionId, true));
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChanged(EnvironmentChangeEvent ignored) {
        onPropertiesRefreshed();
    }

    private void schedule(String sessionId, boolean forceReschedule) {
        if (!properties.isEnabled()) {
            checkAtBySession.remove(sessionId);
            return;
        }

        EventSession eventSession = eventSessionRepository.get(sessionId);
        if (eventSession == null || eventSession.getStatus() != EventSessionStatus.busy) {
            checkAtBySession.remove(sessionId);
            return;
        }

        LocalDateTime checkAt = calculateCheckAt(eventSession);
        LocalDateTime previousCheckAt = checkAtBySession.get(sessionId);
        if (!forceReschedule && previousCheckAt != null && !checkAt.isBefore(previousCheckAt)) {
            return;
        }

        checkAtBySession.put(sessionId, checkAt);
        long delayMillis = Math.max(0L, Duration.between(LocalDateTime.now(), checkAt).toMillis());
        executor.schedule(() -> check(sessionId, checkAt, false, null), delayMillis, TimeUnit.MILLISECONDS);
        log.debug("agent busy recovery check scheduled, sessionId: {}, checkAt: {}", sessionId, checkAt);
    }

    private LocalDateTime calculateCheckAt(EventSession eventSession) {
        LocalDateTime activeAt = eventSession.getLastEventAt();
        if (activeAt == null) {
            activeAt = eventSession.getRunningSince();
        }
        if (activeAt == null) {
            activeAt = LocalDateTime.now();
        }
        return activeAt.plus(properties.getBusyTimeout());
    }

    private boolean check(String sessionId, LocalDateTime expectedCheckAt, boolean force, String reason) {
        if (!properties.isEnabled()) {
            if (expectedCheckAt != null) {
                checkAtBySession.remove(sessionId, expectedCheckAt);
            }
            return false;
        }

        if (!force) {
            LocalDateTime currentCheckAt = checkAtBySession.get(sessionId);
            if (currentCheckAt == null || !currentCheckAt.equals(expectedCheckAt)) {
                return false;
            }
        }

        EventSession eventSession = eventSessionRepository.get(sessionId);
        if (eventSession == null || eventSession.getStatus() != EventSessionStatus.busy) {
            removeCheckpoint(sessionId, expectedCheckAt, force);
            return false;
        }

        if (!agentRequestTaskRepository.hasPending(sessionId)) {
            removeCheckpoint(sessionId, expectedCheckAt, force);
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleAt = calculateCheckAt(eventSession);
        if (now.isBefore(staleAt)) {
            removeCheckpoint(sessionId, expectedCheckAt, force);
            schedule(sessionId, true);
            return false;
        }

        AgentBusyRecoveryHandler recoveryHandler = recoveryHandlerProvider.getIfAvailable();
        if (recoveryHandler == null) {
            log.warn("agent busy recovery handler missing, sessionId: {}", sessionId);
            rescheduleAfterRetryDelay(sessionId, expectedCheckAt, force);
            return false;
        }

        String recoveryReason = reason == null || reason.isBlank()
            ? "Session recovered after busy timeout: " + properties.getBusyTimeout()
            : reason;
        boolean recovered = recoveryHandler.recoverAndContinue(sessionId, recoveryReason);
        removeCheckpoint(sessionId, expectedCheckAt, force);
        if (!recovered) {
            schedule(sessionId, true);
        }
        return recovered;
    }

    private void rescheduleAfterRetryDelay(String sessionId, LocalDateTime expectedCheckAt, boolean force) {
        LocalDateTime retryAt = LocalDateTime.now().plus(properties.getRetryDelay());
        if (force) {
            checkAtBySession.put(sessionId, retryAt);
        } else {
            checkAtBySession.replace(sessionId, expectedCheckAt, retryAt);
        }
        executor.schedule(() -> check(sessionId, retryAt, false, null), properties.getRetryDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void removeCheckpoint(String sessionId, LocalDateTime expectedCheckAt, boolean force) {
        if (force || expectedCheckAt == null) {
            checkAtBySession.remove(sessionId);
        } else {
            checkAtBySession.remove(sessionId, expectedCheckAt);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

}
