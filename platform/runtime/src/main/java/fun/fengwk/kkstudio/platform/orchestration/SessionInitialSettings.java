package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;

import java.util.UUID;

/** Resolves the current Agent and environment defaults before first command acceptance. */
@Service
public class SessionInitialSettings {
  private final AgentRepository agents;
  private final EnvironmentService environments;

  public SessionInitialSettings(AgentRepository agents, EnvironmentService environments) {
    this.agents = agents;
    this.environments = environments;
  }

  public BranchSettings resolve(String agentName, UUID environmentId, ModelSelection model) {
    var agent = agentName == null ? null : agents.getByName(agentName);
    if (agent == null) {
      throw DomainException.validationReason("INVALID_ARGUMENT", "A default agent is required");
    }
    var execution = agent.execution();
    if (model != null) {
      if (!BuiltinExecution.DRIVER.equals(execution.driver())) {
        throw DomainException.validationReason(
            "INVALID_ARGUMENT", "The agent does not support model selection");
      }
      execution = BuiltinExecution.of(model);
    }
    String environment =
        environmentId == null
            ? null
            : environments.requireName(EnvironmentId.parse(environmentId.toString()));
    return new BranchSettings(agentName, execution, environment);
  }
}
