package fun.fengwk.kkstudio.platform.daemon.server;

/**
 * 连接清理的失败收敛。
 *
 * <p>清理要执行多个都会失败的步骤（释放租约、关闭传输），每步都必须在其它步骤失败时仍执行，最后才统一抛出。这里只做聚合与抛出，不做步骤编排：先保留首个 {@link Error}
 * 作为抛出对象并压制后续失败，再决定诊断失败是跟着压制还是单独重抛。
 */
final class DaemonCleanup {

  private final Error error;
  private final Throwable diagnosticFailure;

  private DaemonCleanup(Error error, Throwable diagnosticFailure) {
    this.error = error;
    this.diagnosticFailure = diagnosticFailure;
  }

  /** 汇总两步清理的失败；{@code stage1}/{@code stage2} 为固定阶段名，只用于安全告警。 */
  static DaemonCleanup of(
      String stage1, Throwable failure1, String stage2, Throwable failure2, Log log) {
    Error error = record(null, failure1);
    error = record(error, failure2);
    Throwable diagnosticFailure = null;
    if (failure1 != null) {
      diagnosticFailure = log.log(stage1, failure1, null);
    }
    if (failure2 != null) {
      diagnosticFailure = log.log(stage2, failure2, diagnosticFailure);
    }
    return new DaemonCleanup(error, diagnosticFailure);
  }

  /** 优先抛出清理阶段捕获的未检查失败，其次抛出诊断失败。 */
  void throwIfFailed() {
    if (error != null) {
      if (diagnosticFailure != null && error != diagnosticFailure) {
        error.addSuppressed(diagnosticFailure);
      }
      throw error;
    }
    if (diagnosticFailure != null) {
      if (diagnosticFailure instanceof Error asError) {
        throw asError;
      }
      if (diagnosticFailure instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(
          "cleanup diagnostic failure is not unchecked", diagnosticFailure);
    }
  }

  /** 只有 {@link Error} 参与抛出；其它失败仅用于诊断。 */
  private static Error record(Error first, Throwable failure) {
    if (failure == null) {
      return first;
    }
    if (first == null) {
      return failure instanceof Error error ? error : null;
    }
    if (first != failure) {
      first.addSuppressed(failure);
    }
    return first;
  }

  /** 记录一步失败，返回需要继续累积的诊断失败。 */
  interface Log {

    Throwable log(String stage, Throwable failure, Throwable firstDiagnosticFailure);
  }
}
