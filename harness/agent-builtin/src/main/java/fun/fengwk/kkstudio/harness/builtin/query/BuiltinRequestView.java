package fun.fengwk.kkstudio.harness.builtin.query;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;

import java.util.Objects;
import java.util.UUID;

/** Builtin 当前 generation 冻结请求的明确查询视图。 */
public record BuiltinRequestView(UUID generationId, int attempt, ModelRequestSpec requestSpec) {

  public BuiltinRequestView {
    generationId = Objects.requireNonNull(generationId, "generationId");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    requestSpec = Objects.requireNonNull(requestSpec, "requestSpec");
  }
}
