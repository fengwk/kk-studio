package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.daemon.skill.InstalledSkillPackage;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillPackageInstaller;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillSyncException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 内部 skill 同步能力：原子拉取并解压指定 commit 的技能包。 */
public final class SkillSyncCapability extends AbstractCodingCapability {

  private final SkillPackageInstaller installer;

  public SkillSyncCapability(
      CodingToolsConfig config, SkillPackageInstaller installer, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SYNC));
    this.installer = Objects.requireNonNull(installer, "installer");
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) {
    String callId = request.call().id();
    try {
      JsonNode args = arguments(request);
      String packageName = string(args, "packageName");
      String repositoryUrl = string(args, "repositoryUrl");
      String branch = string(args, "branch");
      String targetCommit = string(args, "targetCommit");
      InstalledSkillPackage installed =
          installer.install(packageName, repositoryUrl, branch, targetCommit);
      ObjectNode node = OBJECT_MAPPER.createObjectNode();
      node.put("packageName", installed.packageName());
      node.put("installedCommit", installed.installedCommit());
      node.put("localPath", installed.localPath());
      return EnvironmentCapabilityResult.json(callId, OBJECT_MAPPER.writeValueAsString(node));
    } catch (SkillSyncException error) {
      return EnvironmentCapabilityResult.codedError(callId, error.code(), error.getMessage());
    } catch (IllegalArgumentException error) {
      return EnvironmentCapabilityResult.codedError(
          callId, "INVALID_ARGUMENT", "Invalid skill sync argument");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return EnvironmentCapabilityResult.error(callId, "Operation cancelled");
    } catch (Exception error) {
      return EnvironmentCapabilityResult.codedError(
          callId, "SKILL_SYNC_FAILED", "Skill sync failed");
    }
  }
}
