package fun.fengwk.kkstudio.core.studio.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.StudioWorkspaces;
import fun.fengwk.kkstudio.studio.model.FunctionRun;
import fun.fengwk.kkstudio.studio.runtime.FunctionCatalog;
import fun.fengwk.kkstudio.studio.runtime.FunctionExecutionRequest;
import fun.fengwk.kkstudio.studio.runtime.FunctionRuntimeService;

import java.util.Objects;
import java.util.Optional;

/**
 * Runtime stub.
 *
 * <p>TODO:
 *
 * <ul>
 *   <li>persist FunctionRun + lease/attempt state
 *   <li>resolve input snapshots from ResourceReference
 *   <li>dispatch system/workflow/agent executors
 *   <li>wire generation providers (text/image/video)
 *   <li>publish ResourceVersion outputs
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class StubFunctionRuntimeService implements FunctionRuntimeService {

  private final FunctionCatalog functionCatalog;

  @Override
  public FunctionRun submit(FunctionExecutionRequest request) {
    Objects.requireNonNull(request, "request");
    StudioWorkspaces.requireDefault(request.workspaceId());
    functionCatalog
        .find(request.functionRef())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown function: " + request.functionRef().functionId()));
    log.info(
        "Rejecting Function submit for {} — runtime adapter not implemented",
        request.functionRef());
    throw new StudioFeatureNotReadyException(
        "FunctionRuntimeService.submit(" + request.functionRef().functionId() + ")");
  }

  @Override
  public Optional<FunctionRun> findRun(long runId) {
    return Optional.empty();
  }

  @Override
  public FunctionRun cancel(long runId) {
    throw new StudioFeatureNotReadyException("FunctionRuntimeService.cancel");
  }
}
