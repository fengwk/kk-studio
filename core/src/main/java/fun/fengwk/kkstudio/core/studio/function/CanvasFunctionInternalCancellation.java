package fun.fengwk.kkstudio.core.studio.function;

/** Run CAS 失效后立即停止 adapter 后续导入的进程内取消信号。 */
final class CanvasFunctionInternalCancellation extends RuntimeException {

  CanvasFunctionInternalCancellation(String message) {
    super(message);
  }
}
