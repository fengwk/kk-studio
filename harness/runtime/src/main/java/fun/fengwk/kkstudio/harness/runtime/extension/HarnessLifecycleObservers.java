package fun.fengwk.kkstudio.harness.runtime.extension;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/** 按 Host 注册顺序发布并隔离 observer 失败。 */
public final class HarnessLifecycleObservers {

  private static final System.Logger LOGGER =
      System.getLogger(HarnessLifecycleObservers.class.getName());

  private final List<HarnessLifecycleObserver> observers;

  public HarnessLifecycleObservers(List<HarnessLifecycleObserver> observers) {
    this.observers = List.copyOf(Objects.requireNonNull(observers, "observers"));
  }

  public void publish(HarnessLifecycleObservation observation) {
    Objects.requireNonNull(observation, "observation");
    for (HarnessLifecycleObserver observer : observers) {
      try {
        observer.observe(observation);
      } catch (RuntimeException error) {
        LOGGER.log(
            Level.WARNING,
            "Harness lifecycle observer failed: " + observer.getClass().getName(),
            error);
      }
    }
  }
}
