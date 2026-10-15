package fun.fengwk.kkstudio.canvas.function;

/**
 * Canvas 域拥有的目录租用端口：按 epoch 提供冻结目录，并冻结其中的函数定义与插件来源。
 *
 * <p>Platform 提供生产实现（按持久目录 epoch 切换），测试可用显式固定或可切换的替身。执行与恢复都先取得某个 epoch 的 {@link Lease}，再从中 {@link
 * Lease#bind(String)} 出 {@link CanvasFunctionBinding}，随后只能通过 {@link
 * Lease#require(CanvasFunctionBinding)} 取回同一 epoch、同一完整定义与来源的注册项；绝不允许按函数名静默退回当前版本。
 */
public interface CanvasFunctionCatalogSource {

  /** 取得当前目录 epoch 的租用。 */
  Lease acquireCurrent();

  /** 取得指定目录 epoch 的租用；该 epoch 已不可用时拒绝。 */
  Lease acquire(long catalogEpoch);

  /** 一次目录租用：其生命周期内只能观察到同一 epoch 的冻结事实，{@link #close()} 不抛受检异常。 */
  interface Lease extends AutoCloseable {

    /** 本次租用固定的目录 epoch。 */
    long epoch();

    /** 本次租用的冻结目录。 */
    CanvasFunctionCatalog catalog();

    /**
     * 从本次租用的目录冻结函数定义与来源。
     *
     * <p>函数未安装时拒绝；内置函数得到的 {@link CanvasFunctionBinding#pluginSource()} 为 {@code null}。
     */
    CanvasFunctionBinding bind(String functionName);

    /**
     * 严格验证绑定属于本次租用：epoch、完整定义与插件来源都必须一致，再返回该注册项。
     *
     * <p>任何一项不匹配都拒绝，不会退回当前同名函数。
     */
    CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding);

    @Override
    void close();
  }
}
