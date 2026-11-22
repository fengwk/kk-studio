package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.UUID;

/**
 * IDE 控制面客户端入参校验：字段缺失、形态错误与 CREATE/RESUME 的显式实例/epoch 约束。
 *
 * <p>目录是可缺省的候选输入：缺省表示零根视图，存在时才校验形态；绑定事实始终以 Daemon 回执的 {@code workspacePath} 与正 bindingEpoch 为准。
 */
public final class IdeInputs {

  private static final int MAX_DIRECTORY_LENGTH = 4096;

  private IdeInputs() {}

  /** 目录：null 表示未指定；非 null 必须非空白、不超过 4096 个码点且不含控制字符（C0、DEL 与 C1）。 */
  public static String requireDirectory(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw DomainException.validation("environment", "directory must not be blank when present");
    }
    if (value.codePointCount(0, value.length()) > MAX_DIRECTORY_LENGTH) {
      throw DomainException.validation(
          "environment", "directory exceeds " + MAX_DIRECTORY_LENGTH + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          "environment", "directory must not contain control characters");
    }
    return value;
  }

  /** 客户端提交的 bindingEpoch：必填且为正数。 */
  static long requireEpoch(Long value) {
    if (value == null || value <= 0) {
      throw DomainException.validation("environment", "bindingEpoch must be positive");
    }
    return value;
  }

  /** 可选的预期 Daemon 实例：null 原样返回，否则必须是 canonical UUID。 */
  static String optionalInstance(String value) {
    if (value == null) {
      return null;
    }
    return requireInstance(value, "expectedDaemonInstanceId must not be blank");
  }

  /**
   * 本节点当前 READY 实例与客户端预期一致时返回该实例。
   *
   * @throws DomainException 预期缺失/非规范形态，或已被其它实例替换
   */
  static String requireCurrentInstance(
      String expectedDaemonInstanceId, String requiredMessage, DaemonConnectionIdentity current) {
    String expected = requireInstance(expectedDaemonInstanceId, requiredMessage);
    if (!expected.equals(current.daemonInstanceId())) {
      throw IdeProtocol.viewGone("environment daemon instance has been replaced");
    }
    return expected;
  }

  /**
   * OPEN 的预期 epoch：CREATE 必须为 0，RESUME 必须为正数。
   *
   * @return CREATE 的 0，或 RESUME 的期望 epoch
   */
  static long requireOpenEpoch(EnvironmentIdeOpenMode mode, Long expectedBindingEpoch) {
    if (expectedBindingEpoch == null) {
      throw DomainException.validation("environment", "expectedBindingEpoch is required");
    }
    if (mode == EnvironmentIdeOpenMode.CREATE) {
      if (expectedBindingEpoch != 0) {
        throw DomainException.validation("environment", "create requires expectedBindingEpoch 0");
      }
      return 0;
    }
    if (expectedBindingEpoch <= 0) {
      throw DomainException.validation(
          "environment", "resume requires a positive expectedBindingEpoch");
    }
    return expectedBindingEpoch;
  }

  private static String requireInstance(String value, String requiredMessage) {
    if (value == null || value.isBlank()) {
      throw DomainException.validation("environment", requiredMessage);
    }
    try {
      return IdeUuids.requireInput(value, "expectedDaemonInstanceId").toString();
    } catch (DomainException error) {
      throw DomainException.validation(
          "environment", "expectedDaemonInstanceId must be a canonical UUID");
    }
  }

  /** 路径字段（Daemon 回执里的目录/workspace）：非空白。 */
  static String requireProtocolPath(String value, String field) {
    if (value == null || value.isBlank()) {
      throw IdeProtocol.protocolFailure("daemon did not return " + field);
    }
    return value;
  }

  /** 可缺省的目录回执：缺省是零根视图的正常事实，存在时仍必须非空白。 */
  static String optionalProtocolPath(String value, String field) {
    if (value == null) {
      return null;
    }
    return requireProtocolPath(value, field);
  }

  /** 客户端提交的 viewId：canonical UUID。 */
  static UUID requireViewId(String value) {
    return IdeUuids.requireInput(value, "viewId");
  }
}
