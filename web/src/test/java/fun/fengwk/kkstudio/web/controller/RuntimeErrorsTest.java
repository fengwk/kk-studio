package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;

/** Harness Runtime 拒绝到 HTTP 状态的唯一翻译契约。 */
class RuntimeErrorsTest {

  @Test
  void translatesDomainThreadNotFoundButPropagatesOtherDomainErrors() {
    // 迁移后的 Thread 不存在继续沿旧信封输出 404，其余领域错误不得被转换。
    DomainException missing = DomainException.notFound("thread", "thread missing");
    ResponseStatusException response =
        assertThrows(
            ResponseStatusException.class,
            () ->
                RuntimeErrors.translate(
                    () -> {
                      throw missing;
                    }));
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertSame(missing, response.getCause());

    for (DomainException other :
        new DomainException[] {
          DomainException.notFound("project", "project missing"),
          DomainException.validation("thread", "invalid"),
          DomainException.conflict("STALE_VERSION", "stale")
        }) {
      assertSame(
          other,
          assertThrows(
              DomainException.class,
              () ->
                  RuntimeErrors.translate(
                      () -> {
                        throw other;
                      })));
    }
  }

  @Test
  void translatesNotFoundTo404PreservingCause() {
    HarnessRuntimeNotFoundException cause = new HarnessRuntimeNotFoundException("missing");
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                RuntimeErrors.translate(
                    () -> {
                      throw cause;
                    }));
    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertSame(cause, error.getCause());
  }

  @Test
  void translatesConflictTo409PreservingCause() {
    HarnessRuntimeConflictException cause =
        new HarnessRuntimeConflictException(
            HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale");
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                RuntimeErrors.translate(
                    () -> {
                      throw cause;
                    }));
    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    assertSame(cause, error.getCause());
  }

  @Test
  void translatesIllegalArgumentTo400PreservingCause() {
    IllegalArgumentException cause = new IllegalArgumentException("bad shape");
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                RuntimeErrors.translate(
                    () -> {
                      throw cause;
                    }));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    assertSame(cause, error.getCause());
  }
}
