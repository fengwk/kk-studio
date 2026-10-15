package fun.fengwk.kkstudio.canvas.function;

import java.util.Objects;

/**
 * 一次 Canvas Function 执行冻结的唯一身份：目录 epoch、完整函数定义与该函数的插件来源。
 *
 * <p>{@code catalogEpoch} 是正整数目录版本，决定用哪一份目录实例执行与恢复；同一 epoch 内的 {@code definition} 与 {@code
 * pluginSource} 不可变。定义是完整事实（名称、说明、args schema、输出计划与引用限制），不是名称或摘要；{@code pluginSource} 为 {@code
 * null} 明确表示内置函数。
 *
 * <p>结合 {@link CanvasFunctionCatalogSource}，执行与恢复都只能按此绑定取得同 epoch 的目录实例，绝不按函数名静默换成当前版本。
 */
public record CanvasFunctionBinding(
    long catalogEpoch,
    CanvasFunctionDefinition definition,
    CanvasFunctionPluginSource pluginSource) {

  public CanvasFunctionBinding {
    if (catalogEpoch <= 0) {
      throw new IllegalArgumentException("catalogEpoch must be positive");
    }
    Objects.requireNonNull(definition, "definition");
    if (pluginSource != null && pluginSource.configRevision() > catalogEpoch) {
      throw new IllegalArgumentException(
          "pluginSource configRevision must not be newer than catalogEpoch");
    }
  }
}
