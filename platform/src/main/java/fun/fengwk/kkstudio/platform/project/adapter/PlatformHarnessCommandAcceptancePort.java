package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.project.port.HarnessCommandAcceptancePort;

import java.util.UUID;

/** {@link HarnessCommandAcceptancePort} 的 platform 宿主适配：以 Issue+Agent owner 复用共享命令接受事务边界。 */
@AllArgsConstructor
@Service
public class PlatformHarnessCommandAcceptancePort implements HarnessCommandAcceptancePort {

  private final HarnessCommandAcceptanceOrchestrator orchestrator;

  @Override
  public AcceptedCommands acceptIssueAgentCommands(
      UUID issueId, String agentName, AcceptCommandsCommand command) {
    return orchestrator.accept(new OwnerRef.IssueAgent(issueId, agentName), command);
  }
}
