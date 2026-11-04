package fun.fengwk.kkstudio.platform.harness.builtin;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.runtime.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.util.Objects;

/** 将 Builtin Agent 的模型选择解析为冻结的 branch execution。 */
@Component
public final class BuiltinAgentExecutionMaterializer {

  private final AgentModelRepository modelRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;

  public BuiltinAgentExecutionMaterializer(
      AgentModelRepository modelRepository, AgentModelRuntimeConfigParser modelConfigParser) {
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
  }

  public ExecutionSelection materialize(String agentName, ExecutionSelection execution) {
    ModelSelection selection = BuiltinExecution.model(execution);
    AgentModel model = modelRepository.getByName(selection.modelName());
    if (model == null) {
      throw new IllegalArgumentException("subagent model not found: " + selection.modelName());
    }
    if (model.kind() == AgentModelKind.GROUP) {
      if (selection.variant() != null) {
        throw new IllegalArgumentException(
            "subagent model group must not select a variant: " + agentName);
      }
      return BuiltinExecution.of(ModelSelection.group(selection.modelName()));
    }
    ParsedAgentModelConfig parsed;
    try {
      parsed = modelConfigParser.parse(model.configJson());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "invalid subagent model configuration: " + agentName, error);
    }
    String variantName =
        selection.variant() == null ? parsed.defaultVariant() : selection.variant();
    parsed.variants().stream()
        .filter(candidate -> candidate.id().equals(variantName))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "subagent model variant not found: " + agentName + " variant=" + variantName));
    return BuiltinExecution.of(ModelSelection.model(selection.modelName(), variantName));
  }
}
