package fun.fengwk.kkstudio.platform.harness.thread.query;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.interaction.EnvironmentToolWait;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 将引擎 Work 事实与平台环境租约、名称组合为线程工具行的读取时投影。 */
@Service
public class EnvironmentWaitProjectionService {

  private final HarnessRuntime runtime;
  private final EnvironmentService environments;

  public EnvironmentWaitProjectionService(HarnessRuntime runtime, EnvironmentService environments) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.environments = Objects.requireNonNull(environments, "environments");
  }

  public void project(HarnessThreadSnapshotDTO snapshot) {
    Map<UUID, ToolInvocationDTO> tools = new HashMap<>();
    for (ToolInvocationDTO tool : snapshot.getToolInvocations()) {
      tools.put(UUID.fromString(tool.getId()), tool);
    }
    Map<EnvironmentId, String> names = new HashMap<>();
    Map<EnvironmentId, Optional<Instant>> leases = new HashMap<>();
    for (EnvironmentToolWait fact : runtime.listEnvironmentToolWaits(tools.keySet())) {
      ToolInvocationDTO tool = tools.get(fact.invocationId());
      EnvironmentId id = fact.environmentId();
      if (id == null) {
        continue;
      }
      tool.setRequiredEnvironmentId(id.toString());
      tool.setRequiredEnvironmentName(names.computeIfAbsent(id, environments::requireName));
      Instant leaseUntil =
          fact.invocationReady()
              ? leases.computeIfAbsent(id, environments::findReadyLeaseUntil).orElse(null)
              : null;
      tool.setWaitingForEnvironment(fact.workAvailable() && leaseUntil == null);
      Instant workDeadline = fact.workFreshnessAt();
      tool.setEnvironmentWaitFreshnessAt(
          leaseUntil == null || (workDeadline != null && workDeadline.isBefore(leaseUntil))
              ? workDeadline
              : leaseUntil);
    }
  }
}
