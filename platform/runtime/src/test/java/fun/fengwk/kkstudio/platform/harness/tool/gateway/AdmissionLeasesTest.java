package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AdmissionLeases}：两个租约的释放顺序与清理失败的 suppressed 语义。
 *
 * <p>生产的并发租约关闭幂等且不抛异常，清理失败只能由目录租用或替身配额租约产生，因此这里直接以替身租约覆盖抑制分支。
 */
class AdmissionLeasesTest {

  @Test
  void releaseKeepsCatalogFailureAndSuppressesQuotaReleaseFailure() {
    IllegalStateException catalogFailure = new IllegalStateException("catalog close failed");
    IllegalStateException quotaFailure = new IllegalStateException("quota release failed");
    AtomicBoolean quotaReleased = new AtomicBoolean();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AdmissionLeases.release(
                    failingCatalogLease(catalogFailure),
                    () -> {
                      quotaReleased.set(true);
                      throw quotaFailure;
                    }));

    assertSame(catalogFailure, thrown);
    assertArrayEquals(new Throwable[] {quotaFailure}, thrown.getSuppressed());
    assertTrue(quotaReleased.get(), "quota must be released even when the catalog close fails");
  }

  @Test
  void releaseAfterFailureSuppressesCleanupFailureOnThePrimaryFailure() {
    AssertionError primary = new AssertionError("admission failed");
    IllegalStateException catalogFailure = new IllegalStateException("catalog close failed");
    AtomicBoolean quotaReleased = new AtomicBoolean();

    AdmissionLeases.releaseAfterFailure(
        failingCatalogLease(catalogFailure), () -> quotaReleased.set(true), primary);

    assertArrayEquals(new Throwable[] {catalogFailure}, primary.getSuppressed());
    assertTrue(quotaReleased.get(), "quota must be released when admission fails");
  }

  private static RuntimeToolCatalogSource.Lease failingCatalogLease(RuntimeException failure) {
    return new RuntimeToolCatalogSource.Lease() {
      @Override
      public HarnessCatalog harnessCatalog() {
        throw new UnsupportedOperationException("not used by lease release");
      }

      @Override
      public RuntimeToolCatalog catalog() {
        throw new UnsupportedOperationException("not used by lease release");
      }

      @Override
      public ToolContribution require(ToolBinding binding) {
        throw new UnsupportedOperationException("not used by lease release");
      }

      @Override
      public void close() {
        throw failure;
      }
    };
  }
}
