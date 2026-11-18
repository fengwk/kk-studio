package fun.fengwk.kkstudio.platform.harness.thread.query;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.interaction.EnvironmentToolWait;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

  /** 批次内只读取一次权威等待事实，并把相同工具事实投影到各份快照 DTO。 */
  public void project(List<HarnessThreadSnapshotDTO> snapshots) {
    Map<UUID, List<ToolInvocationDTO>> tools = new HashMap<>();
    for (HarnessThreadSnapshotDTO snapshot : snapshots) {
      for (ToolInvocationDTO tool : snapshot.getToolInvocations()) {
        tools
            .computeIfAbsent(UUID.fromString(tool.getId()), ignored -> new ArrayList<>())
            .add(tool);
      }
    }
    Map<EnvironmentId, String> names = new HashMap<>();
    Map<EnvironmentId, Optional<Instant>> leases = new HashMap<>();
    for (EnvironmentToolWait fact : runtime.listEnvironmentToolWaits(tools.keySet())) {
      List<ToolInvocationDTO> matchingTools = tools.get(fact.invocationId());
      EnvironmentId id = fact.environmentId();
      if (id == null) {
        continue;
      }
      String name = names.computeIfAbsent(id, environments::requireName);
      Instant leaseUntil =
          fact.invocationReady()
              ? leases.computeIfAbsent(id, environments::findReadyLeaseUntil).orElse(null)
              : null;
      Instant workDeadline = fact.workFreshnessAt();
      for (ToolInvocationDTO tool : matchingTools) {
        tool.setRequiredEnvironmentId(id.toString());
        tool.setRequiredEnvironmentName(name);
        tool.setWaitingForEnvironment(fact.workAvailable() && leaseUntil == null);
        tool.setEnvironmentWaitFreshnessAt(
            leaseUntil == null || (workDeadline != null && workDeadline.isBefore(leaseUntil))
                ? workDeadline
                : leaseUntil);
      }
    }
  }
}
