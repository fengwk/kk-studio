package fun.fengwk.kkstudio.harness.runtime.context;

/** 按 Host 提供顺序执行的 Context transform。 */
@FunctionalInterface
public interface ContextTransform {
  ContextState transform(ContextState state);
}
