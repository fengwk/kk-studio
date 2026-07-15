package fun.fengwk.kkstudio.harness.runtime.extension;

import java.util.Objects;

/** 标识扩展 contribution 或 disposer 失败的明确边界异常。 */
public final class HarnessExtensionException extends RuntimeException {

  public enum Phase {
    CONTRIBUTE,
    DISPOSE
  }

  private final String extensionId;
  private final Phase phase;

  HarnessExtensionException(String extensionId, Phase phase, RuntimeException cause) {
    super("extension '" + extensionId + "' failed during " + phaseText(phase), cause);
    this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
    this.phase = Objects.requireNonNull(phase, "phase");
  }

  public String extensionId() {
    return extensionId;
  }

  public Phase phase() {
    return phase;
  }

  private static String phaseText(Phase phase) {
    return switch (Objects.requireNonNull(phase, "phase")) {
      case CONTRIBUTE -> "contribute";
      case DISPOSE -> "dispose";
    };
  }
}
