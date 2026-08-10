package fun.fengwk.kkstudio.studio.canvas.function;

import java.util.List;

/** 一个 provider adapter 可声明并执行一个或多个 Canvas Function model。 */
public interface CanvasFunctionAdapter {

  List<CanvasFunctionModel> models();

  boolean enabled();

  String unavailableReason();

  void preflight(CanvasFunctionFrozenRun run);

  List<Long> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run);

  default void cancel(CanvasFunctionFrozenRun run) {}
}
