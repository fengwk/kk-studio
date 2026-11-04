package fun.fengwk.kkstudio.platform.catalog.definition;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.util.Objects;

/** Builtin 执行选择的模型身份和 catalog 引用校验。 */
@Component
public final class BuiltinAgentExecutionValidator {

  private static final String RESOURCE = "agent_definition";
  private final AgentModelRepository modelRepository;
  private final AgentModelDefaultVariantResolver variantResolver;

  public BuiltinAgentExecutionValidator(
      AgentModelRepository modelRepository, AgentModelDefaultVariantResolver variantResolver) {
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.variantResolver = Objects.requireNonNull(variantResolver, "variantResolver");
  }

  /** 导入预检只验证身份形状，不依赖尚未导入的模型。 */
  public void validateShape(ExecutionSelection execution) {
    if (execution != null && BuiltinExecution.DRIVER.equals(execution.driver())) {
      model(execution);
    }
  }

  /** 必须在取得任何 Agent 行锁之前调用，持有模型行锁直至事务结束。 */
  public void validateCatalog(ExecutionSelection execution) {
    if (execution == null || !BuiltinExecution.DRIVER.equals(execution.driver())) {
      return;
    }
    ModelSelection selection = model(execution);
    AgentModel model = modelRepository.getByNameForUpdate(selection.modelName());
    if (model == null) {
      throw new AiResourceNotFoundException("agent_model");
    }
    String variant = selection.variant();
    if (variant == null) {
      return;
    }
    if (model.kind() == AgentModelKind.GROUP) {
      throw new AiValidationException(
          RESOURCE, "variant must be blank when the selected model is a GROUP");
    }
    try {
      variantResolver.resolve(model.name(), variant);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  private ModelSelection model(ExecutionSelection execution) {
    try {
      ModelSelection selection = BuiltinExecution.model(execution);
      if (selection.variant() != null && selection.variant().length() > 64) {
        throw new IllegalArgumentException("variant must be <= 64 characters");
      }
      return selection;
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }
}
