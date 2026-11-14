package fun.fengwk.kkstudio.plugin.comfyui;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;

/** 冻结 Run 的存活守卫：任何出网、暂存与物化动手前都先确认 Run 仍在运行。 */
final class ComfyuiRunGuard {

  private ComfyuiRunGuard() {}

  static void ensureRunning(CanvasFunctionExecutionContext context) {
    if (!context.isRunning()) {
      throw new IllegalStateException("Canvas Function run is no longer RUNNING");
    }
  }
}
