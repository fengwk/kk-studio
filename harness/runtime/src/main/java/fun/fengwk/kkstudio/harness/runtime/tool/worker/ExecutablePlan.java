package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * The frozen final plan that the Tool worker must execute: route plus the canonical Tool call.
 *
 * <p>Produced exclusively by {@link PermissionResolver} after the durable permission transition has
 * been persisted. The {@code call} reflects the descriptor name and arguments that were written to
 * the row; the {@code binding} carries the route the worker must dispatch on, both of which must
 * stay aligned with the durable plan for the rest of the invocation's lifecycle.
 *
 * @param binding final route (optional environment name + frozen descriptor)
 * @param call tool call (id + name + arguments) reconstructed from the persisted final plan
 */
record ExecutablePlan(ToolBinding binding, ToolCall call) {

  ExecutablePlan {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(call, "call");
  }
}
