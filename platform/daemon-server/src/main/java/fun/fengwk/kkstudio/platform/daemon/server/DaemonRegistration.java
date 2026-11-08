package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;

/** 注册凭据解析出的环境身份。 */
public record DaemonRegistration(EnvironmentId environmentId) {

  public DaemonRegistration {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
  }
}
