package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.project.port.AgentBranchSettingsPort;

/** {@link AgentBranchSettingsPort} 的 platform 宿主适配：按当前宿主 Agent/Model catalog 物化分支设置。 */
@AllArgsConstructor
@Service
public class PlatformAgentBranchSettingsPort implements AgentBranchSettingsPort {

  private final AgentBranchSettingsMaterializer materializer;

  @Override
  public BranchSettings materializeBranchSettings(String agentName) {
    return materializer.materialize(agentName);
  }
}
