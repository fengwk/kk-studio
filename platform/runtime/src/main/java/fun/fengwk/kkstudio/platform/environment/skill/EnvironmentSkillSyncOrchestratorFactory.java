package fun.fengwk.kkstudio.platform.environment.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 持有 Environment Skill 同步所需的 Platform 侧依赖，让 Web 组合根只提供 Environment 会话传输与 executor。 */
@Component
public final class EnvironmentSkillSyncOrchestratorFactory {

  private final EnvironmentRegistry environmentRegistry;
  private final SkillPackageRepository skillPackageRepository;
  private final Clock clock;

  public EnvironmentSkillSyncOrchestratorFactory(
      EnvironmentRegistry environmentRegistry,
      SkillPackageRepository skillPackageRepository,
      Clock clock) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public EnvironmentSkillSyncOrchestrator create(
      EnvironmentCapabilityTransport capabilityTransport, ExecutorService executor) {
    return new EnvironmentSkillSyncOrchestrator(
        environmentRegistry,
        skillPackageRepository,
        Objects.requireNonNull(capabilityTransport, "capabilityTransport"),
        Objects.requireNonNull(executor, "executor"),
        clock);
  }
}
