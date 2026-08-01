package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallResult;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteTool;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Permission decision and executable-plan resolution for a claimed Tool invocation.
 *
 * <p>The resolver is the sole gate between a successful durable claim and any external Tool I/O. It
 * runs strictly before {@link ExecutionCallback} is constructed and never invokes a {@link Tool}.
 * The three durability outcomes it may produce are:
 *
 * <ul>
 *   <li>ALLOW — atomically overwrite the persisted final plan and flip permission to {@code
 *       ALLOWED}, returning an {@link ExecutablePlan} that matches the row.
 *   <li>ASK — atomically transition to {@code WAITING_INTERACTION + ASKED}, park the durable
 *       target, and insert exactly one OPEN Interaction; the resolver returns {@link
 *       PermissionResolution.AwaitingApproval}.
 *   <li>DENY — atomically transition to terminal {@code FAILED + DENIED} with {@code
 *       PERMISSION_DENIED} and activate the next environment route head; the resolver returns
 *       {@link PermissionResolution.Denied}.
 * </ul>
 *
 * <p>Boundary failures, malformed boundary outputs, route instability, lost ownership between claim
 * and persistence, and pre-claim invariant breaches converge to a terminal {@code FAILED} row with
 * the {@code PERMISSION_BOUNDARY_MISSING} (or {@code PERMISSION_DENIED}) kind; the resolver returns
 * {@link PermissionResolution.TerminalFailed} so the worker stops without invoking any external
 * Tool I/O.
 *
 * <p>The {@code ALLOWED} branch skips the chain entirely and reconstructs the {@link
 * ExecutablePlan} from the persisted final plan; an empty chain still works because no boundary is
 * required when the row already carries an approved plan.
 */
final class PermissionResolver {

  private final ToolInvocationTransactions transactions;
  private final ToolInterceptorChain interceptorChain;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ToolRegistry registry;
  private final RemoteToolTransport remoteTransport;
  private final Clock clock;
  private final Path workdir;
  private final Path environmentRoot;

  PermissionResolver(
      ToolInvocationTransactions transactions,
      ToolInterceptorChain interceptorChain,
      ToolSettingsProvider toolSettingsProvider,
      ToolRegistry registry,
      RemoteToolTransport remoteTransport,
      Clock clock,
      Path workdir,
      Path environmentRoot) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.workdir = Objects.requireNonNull(workdir, "workdir").toAbsolutePath().normalize();
    this.environmentRoot =
        Objects.requireNonNull(environmentRoot, "environmentRoot").toAbsolutePath().normalize();
  }

  /**
   * Resolve the post-claim permission transition for the freshly claimed invocation. The caller
   * must not perform any external Tool I/O on a non-{@link PermissionResolution.Resolved} outcome.
   */
  PermissionResolution resolve(ClaimedToolInvocation claimed) {
    Objects.requireNonNull(claimed, "claimed");
    ToolInvocation invocation = claimed.invocation();
    ToolPermissionState state = invocation.permissionState();
    if (state == ToolPermissionState.ASKED || state == ToolPermissionState.DENIED) {
      throw new IllegalStateException(
          "Tool invocation "
              + invocation.id()
              + " reached a claimed worker with permissionState="
              + state);
    }
    ToolBinding binding = bindingFor(invocation);
    ToolCall originalCall =
        new ToolCall(invocation.toolCallId(), invocation.toolName(), invocation.argumentsJson());
    if (state == ToolPermissionState.PENDING) {
      return resolvePending(claimed, binding, originalCall);
    }
    if (state == ToolPermissionState.ALLOWED) {
      return resolveAllowed(binding, originalCall, invocation);
    }
    // ASKED / DENIED are non-claimable; the durable row is in a state the worker must never
    // execute. The dispatcher claim precondition already filters these, so reaching this branch is
    // an invariant breach. Throw to surface the bug rather than fabricate a terminal row: the
    // existing lease will expire and the durable row's state machine (recoverExpired for ALLOWED,
    // lease-expiry scan for others) re-routes correctly.
    throw new IllegalStateException(
        "Tool invocation "
            + invocation.id()
            + " reached a claimed worker with permissionState="
            + state);
  }

  private PermissionResolution resolveAllowed(
      ToolBinding binding, ToolCall originalCall, ToolInvocation invocation) {
    // Already approved: skip the chain and use the persisted final plan (descriptor, arguments,
    // route, and environment name are all already in the row). The ToolCall is reconstructed from
    // the persisted arguments, not the original frozen provider call.
    ToolCall call =
        new ToolCall(originalCall.id(), binding.descriptor().name(), invocation.argumentsJson());
    return new PermissionResolution.Resolved(new ExecutablePlan(binding, call));
  }

  private PermissionResolution resolvePending(
      ClaimedToolInvocation claimed, ToolBinding binding, ToolCall originalCall) {
    BeforeToolCallResult decision;
    try {
      decision = evaluatePermission(binding, originalCall, claimed.invocation());
    } catch (RuntimeException boundaryError) {
      // Boundary failed (no boundary, threw, or returned a malformed result): fail closed with a
      // terminal FAILED, no Tool I/O. The dispatch context is still owned; completeFailure walks
      // the standard terminal path.
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING",
              "Tool permission boundary did not produce a decision: " + boundaryError.getMessage()),
          claimed.invocation().lastActivityAt());
    }
    PermissionAction action = decision.permissionAction();
    PermissionPromptPreview preview = decision.permissionPromptPreview();
    if (action == null) {
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING", "Tool permission boundary returned a null action."),
          claimed.invocation().lastActivityAt());
    }
    if (action == PermissionAction.ASK && preview == null) {
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING",
              "Tool permission boundary returned ASK without a prompt preview."),
          claimed.invocation().lastActivityAt());
    }
    // Route stability: the permission boundary must not silently move an active target across
    // ENVIRONMENT routes. PLATFORM (null route) is preserved. Descriptor/arguments may change.
    if (!Objects.equals(decision.binding().environmentName(), binding.environmentName())) {
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING",
              "Tool permission boundary attempted to change execution route from "
                  + binding.environmentName()
                  + " to "
                  + decision.binding().environmentName()),
          claimed.invocation().lastActivityAt());
    }
    switch (action) {
      case ALLOW:
        return persistAllow(claimed, originalCall, decision);
      case DENY:
        return persistDeny(claimed);
      case ASK:
        return persistAsk(claimed, decision, preview);
      default:
        return terminalFailure(
            claimed,
            new ToolInvocationError(
                "PERMISSION_BOUNDARY_MISSING",
                "Tool permission boundary returned an unsupported action: " + action),
            claimed.invocation().lastActivityAt());
    }
  }

  private PermissionResolution persistAllow(
      ClaimedToolInvocation claimed, ToolCall originalCall, BeforeToolCallResult decision) {
    ToolInvocationUpdateOutcome outcome =
        transactions.persistPermissionAllowed(
            claimed, decision.binding(), decision.argumentsJson(), clock.instant());
    if (outcome != ToolInvocationUpdateOutcome.APPLIED) {
      // Lost ownership between claim and persistence: the durable row has moved on (e.g. expired
      // lease recovery). Fail closed with no external I/O.
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING",
              "Tool permission boundary did not converge; ownership was lost."),
          claimed.invocation().lastActivityAt());
    }
    // The final ToolCall must use the decision's arguments JSON so that the actually executed plan
    // matches the persisted final plan. tool name and call id are immutable.
    ToolCall executeCall =
        new ToolCall(
            originalCall.id(), decision.binding().descriptor().name(), decision.argumentsJson());
    return new PermissionResolution.Resolved(new ExecutablePlan(decision.binding(), executeCall));
  }

  private PermissionResolution persistDeny(ClaimedToolInvocation claimed) {
    ToolInvocationUpdateOutcome outcome = transactions.denyPermission(claimed, clock.instant());
    if (outcome != ToolInvocationUpdateOutcome.APPLIED) {
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING",
              "Tool permission boundary did not converge; ownership was lost."),
          claimed.invocation().lastActivityAt());
    }
    return new PermissionResolution.Denied();
  }

  private PermissionResolution persistAsk(
      ClaimedToolInvocation claimed,
      BeforeToolCallResult decision,
      PermissionPromptPreview preview) {
    ToolInvocationUpdateOutcome outcome =
        transactions.awaitPermission(
            claimed, decision.binding(), decision.argumentsJson(), preview, clock.instant());
    if (outcome != ToolInvocationUpdateOutcome.APPLIED) {
      return terminalFailure(
          claimed,
          new ToolInvocationError(
              "PERMISSION_BOUNDARY_MISSING",
              "Tool permission boundary did not converge; ownership was lost."),
          claimed.invocation().lastActivityAt());
    }
    return new PermissionResolution.AwaitingApproval();
  }

  private PermissionResolution.TerminalFailed terminalFailure(
      ClaimedToolInvocation claimed, ToolInvocationError error, Instant lastObservedActivityAt) {
    transactions.completeFailure(claimed, error, lastObservedActivityAt, clock.instant());
    return new PermissionResolution.TerminalFailed();
  }

  /**
   * Resolves the Tool that executes the persisted final plan. A non-null Environment target always
   * routes remotely, even when a local registry contains a same-named descriptor.
   */
  Optional<Tool> resolveTool(ToolBinding binding) {
    if (binding.environmentName() != null) {
      return Optional.of(
          new RemoteTool(binding.descriptor(), binding.environmentName(), remoteTransport));
    }
    Optional<Tool> local =
        registry.find(binding.descriptor().name(), binding.descriptor().version());
    if (local.isPresent() && descriptorMatches(binding, local.get())) {
      return local;
    }
    return Optional.empty();
  }

  /**
   * Failure message used when the persisted descriptor is missing or has drifted. Kept here so
   * callers can produce a deterministic tool-missing failure without depending on the underlying
   * registry transport.
   */
  String toolMissingFailure(ToolBinding binding) {
    return "Frozen tool "
        + binding.descriptor().name()
        + "@"
        + binding.descriptor().version()
        + " is unavailable.";
  }

  private BeforeToolCallResult evaluatePermission(
      ToolBinding binding, ToolCall originalCall, ToolInvocation invocation) {
    if (!interceptorChain.hasPermissionBoundary()) {
      throw new IllegalStateException(
          "Tool interceptor chain is missing a permission boundary for invocation "
              + invocation.id());
    }
    ToolSettings settings = toolSettingsProvider.get();
    try {
      return interceptorChain.before(
          binding, originalCall, settings, invocation.yoloEnabled(), workdir, environmentRoot);
    } catch (RuntimeException error) {
      throw new IllegalStateException(
          "Tool permission boundary threw for invocation " + invocation.id(), error);
    }
  }

  private static ToolBinding bindingFor(ToolInvocation invocation) {
    return ToolBinding.of(invocation.descriptor(), invocation.environmentName());
  }

  private static boolean descriptorMatches(ToolBinding binding, Tool tool) {
    return tool.descriptor().equals(binding.descriptor());
  }
}
