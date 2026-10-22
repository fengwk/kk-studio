/**
 * 与业务无关的有界调度骨架：{@link fun.fengwk.kkstudio.common.concurrent.BoundedWorkDispatcher} 用合并 wake 的单
 * drain、bounded worker handoff、周期 poll 与 stop 生命周期驱动一次「claim -&gt; 处理」循环。
 *
 * <p>本包只提供同步调度与资源计数，不读取或改写任何业务状态：{@code claimNext}、{@code execute}、{@code returnUnstartedClaim}
 * 三个回调由调用方注入并保有全部业务语义，调度骨架只保证回调的调用时机、串行性与容量约束。回调的 所有权约定、executor 的 fail-fast 与生命周期要求、stop 的收尾语义都记录在
 * {@code BoundedWorkDispatcher} 的类型注释里。
 */
package fun.fengwk.kkstudio.common.concurrent;
