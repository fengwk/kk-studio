package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;

/**
 * Canvas 域的目录租用端口：每个 {@link Lease} 固定当前函数目录，租用期间其中的 adapter 实例不会被关闭。
 *
 * <p>启动执行时从租用 {@link Lease#bind(String)} 出 {@link CanvasFunctionBinding}；执行时通过 {@link
 * Lease#require(CanvasFunctionBinding)} 取回定义一致的注册项。
 */
public interface CanvasFunctionCatalogSource {

  /** 租用当前目录。 */
  Lease acquire();

  /** 一次目录租用；{@link #close()} 不抛受检异常。 */
  interface Lease extends AutoCloseable {

    /** 本次租用的目录。 */
    CanvasFunctionCatalog catalog();

    /** 冻结当前目录中该函数的完整定义；函数未安装时拒绝。 */
    CanvasFunctionBinding bind(String functionName);

    /**
     * 取回与绑定定义一致的注册项。
     *
     * @throws IllegalStateException 函数已不存在或定义已变化
     */
    CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding);

    @Override
    void close();
  }
}
