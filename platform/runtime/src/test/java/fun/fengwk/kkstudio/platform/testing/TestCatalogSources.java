package fun.fengwk.kkstudio.platform.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;

/**
 * 目录租用测试替身：把给定的只读目录视图暴露成单租用 {@link RuntimeToolCatalogSource}。
 *
 * <p>只替代 epoch 对账本身（current 返回固定租用），租用内的一致性语义仍由被测代码负责，因此不掩盖「一次租用内完成校验」的断言。
 */
public final class TestCatalogSources {

  private TestCatalogSources() {}

  /** 单租用 source：{@code acquireCurrent()} 返回固定租用。 */
  public static RuntimeToolCatalogSource singleLease(RuntimeToolCatalog catalog) {
    RuntimeToolCatalogSource catalogSource = mock(RuntimeToolCatalogSource.class);
    RuntimeToolCatalogSource.Lease lease = mock(RuntimeToolCatalogSource.Lease.class);
    when(catalogSource.acquireCurrent()).thenReturn(lease);
    when(lease.catalog()).thenReturn(catalog);
    return catalogSource;
  }
}
