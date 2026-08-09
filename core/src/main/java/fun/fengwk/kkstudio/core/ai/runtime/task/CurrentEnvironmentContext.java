package fun.fengwk.kkstudio.core.ai.runtime.task;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** 单次普通模型调用冻结的当前 Environment prompt 上下文。 */
public record CurrentEnvironmentContext(
    EnvironmentName name,
    Status status,
    DaemonEnvironmentInfo environment,
    ZonedDateTime currentDateTime) {

  public enum Status {
    READY("ready"),
    UNAVAILABLE("unavailable"),
    NONE("none");

    private final String promptValue;

    Status(String promptValue) {
      this.promptValue = promptValue;
    }

    public String promptValue() {
      return promptValue;
    }
  }

  public CurrentEnvironmentContext {
    status = Objects.requireNonNull(status, "status");
    currentDateTime = Objects.requireNonNull(currentDateTime, "currentDateTime");
    if (status == Status.NONE && (name != null || environment != null)) {
      throw new IllegalArgumentException(
          "NONE environment context must not carry a name or metadata");
    }
    if (status != Status.NONE && name == null) {
      throw new IllegalArgumentException("selected environment context must carry a name");
    }
    if (status == Status.READY && environment == null) {
      throw new IllegalArgumentException("READY environment context must carry metadata");
    }
    if (environment != null
        && !currentDateTime.getZone().equals(ZoneId.of(environment.timeZone()))) {
      throw new IllegalArgumentException(
          "currentDateTime zone must match environment metadata timeZone");
    }
  }
}
