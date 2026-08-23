package fun.fengwk.kkstudio.canvas.function;

import java.util.List;
import java.util.UUID;

/** 一个 provider adapter 可声明并执行一个或多个 Canvas Function model。 */
public interface CanvasFunctionAdapter {

  List<CanvasFunctionModel> models();

  boolean enabled();

  String unavailableReason();

  void preflight(CanvasFunctionFrozenRun run);

  List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run);

  default void cancel(CanvasFunctionFrozenRun run) {}
}
