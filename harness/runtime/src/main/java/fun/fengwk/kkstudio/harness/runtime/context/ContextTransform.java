package fun.fengwk.kkstudio.harness.runtime.context;

/** 扩展 Context transform；数值越小越先执行。 */
@FunctionalInterface
public interface ContextTransform {
  ContextState transform(ContextState state);

  default int priority() {
    return 0;
  }
}
