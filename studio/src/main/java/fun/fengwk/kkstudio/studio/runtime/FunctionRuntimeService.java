package fun.fengwk.kkstudio.studio.runtime;

import fun.fengwk.kkstudio.studio.model.FunctionRun;

import java.util.Optional;

/** Runtime entry for Function execution. */
public interface FunctionRuntimeService {

  FunctionRun submit(FunctionExecutionRequest request);

  Optional<FunctionRun> findRun(long runId);

  FunctionRun cancel(long runId);
}
