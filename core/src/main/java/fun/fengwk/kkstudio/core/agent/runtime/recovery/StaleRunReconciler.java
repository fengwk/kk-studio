package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时把长时间停留在 queued / running 的 run 标记为 failed。
 *
 * <p>事件流是 append-only 日志，崩溃遗留的 run 不会被回滚；如果不清理， 这些 run 会永远停留在非终态，UI 上的 active run 计数会失真。
 *
 * @author fengwk
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleRunReconciler {

  private static final Duration STALE_RUN_THRESHOLD = Duration.ofMinutes(30);

  private static final List<String> ACTIVE_STATUSES = List.of("queued", "running");

  private final AgentRunRepository agentRunRepository;
  private final AgentRunService agentRunService;

  @PostConstruct
  public void reconcile() {
    LocalDateTime threshold = LocalDateTime.now().minus(STALE_RUN_THRESHOLD);
    List<AgentRun> staleRuns = agentRunRepository.listStaleRuns(threshold);
    if (staleRuns.isEmpty()) {
      log.info("StaleRunReconciler: no stale runs (threshold={})", threshold);
      return;
    }

    int failed = 0;
    for (AgentRun run : staleRuns) {
      if (agentRunService.markFailed(run.getRunId())) {
        failed++;
      } else {
        log.warn(
            "StaleRunReconciler: failed to mark runId={} as failed (already terminal?)",
            run.getRunId());
      }
    }
    log.info(
        "StaleRunReconciler: reconciled {} stale runs out of {} (threshold={})",
        failed,
        staleRuns.size(),
        threshold);

    // sanity check: listRuns should now show only terminal status for these runIds
    for (AgentRun run : staleRuns) {
      AgentRunDTO current = agentRunService.getRun(run.getRunId());
      if (current != null && ACTIVE_STATUSES.contains(current.getStatus())) {
        log.error(
            "StaleRunReconciler: runId={} still active after reconcile (status={})",
            run.getRunId(),
            current.getStatus());
      }
    }
  }
}
