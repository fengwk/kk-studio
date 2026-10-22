package fun.fengwk.kkstudio.canvas.function;

import java.util.Objects;

/**
 * 一次 Canvas Function 执行冻结的完整函数定义（名称、说明、args schema、输出计划与引用限制）。
 *
 * <p>执行前通过 {@link CanvasFunctionCatalogSource.Lease#require(CanvasFunctionBinding)}
 * 在当前目录中取回同名函数，并要求定义完全一致；函数已移除或定义已变化时拒绝，绝不按新定义执行旧输入。
 */
public record CanvasFunctionBinding(CanvasFunctionDefinition definition) {

  public CanvasFunctionBinding {
    Objects.requireNonNull(definition, "definition");
  }
}
