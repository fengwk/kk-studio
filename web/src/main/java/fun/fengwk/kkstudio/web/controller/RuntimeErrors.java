package fun.fengwk.kkstudio.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 同步 Harness Runtime 拒绝到 HTTP 状态的唯一翻译：未找到 → 404、业务冲突 → 409、非法请求/DTO → 400。
 *
 * <p>认证、实际资源权限与请求格式仍是硬边界；这里只保留 typed 异常的类型信息（cause 供 advice 输出稳定 reason）。
 */
final class RuntimeErrors {

  private static final Set<String> HARNESS_RESOURCES =
      Set.of("thread", "session", "entry", "execution", "agent_task");

  private RuntimeErrors() {}

  static <T> T translate(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DomainException error) {
      if (error.code() == DomainErrorCode.NOT_FOUND
          && error.resource() != null
          && HARNESS_RESOURCES.contains(error.resource())) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
      }
      if (error.code() == DomainErrorCode.CONFLICT
          && Arrays.stream(HarnessConflictReason.values())
              .anyMatch(reason -> reason.name().equals(error.reason()))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
      }
      throw error;
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
