package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission;

/**
 * 准入租约的释放顺序：目录租用先释放，并发配额必定独立释放。
 *
 * <p>目录租用钉住本次调用仍可能访问的 Plugin 实例，因此只在终态决议提交之后、执行体返回之后释放；清理自身失败不替换主异常，只作为 suppressed 附加。
 */
final class AdmissionLeases {

  private AdmissionLeases() {}

  /** 释放两个租约；目录关闭失败时仍释放并发配额，并原样抛出目录关闭失败。 */
  static void release(
      RuntimeToolCatalogSource.Lease catalogLease, ConcurrencyAdmission.Lease admissionLease) {
    try {
      if (catalogLease != null) {
        catalogLease.close();
      }
    } catch (RuntimeException | Error failure) {
      try {
        admissionLease.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failure) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
    admissionLease.close();
  }

  /** 已有主异常时，清理失败只附加到原异常，不替换它。 */
  static void releaseAfterFailure(
      RuntimeToolCatalogSource.Lease catalogLease,
      ConcurrencyAdmission.Lease admissionLease,
      Throwable failure) {
    try {
      release(catalogLease, admissionLease);
    } catch (RuntimeException | Error cleanup) {
      if (cleanup != failure) {
        failure.addSuppressed(cleanup);
      }
    }
  }
}
