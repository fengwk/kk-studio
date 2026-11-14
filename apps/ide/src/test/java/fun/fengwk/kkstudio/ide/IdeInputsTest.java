package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

/** 意图：客户端入参的错误码/文案保持原样；CREATE 与 RESUME 的显式实例与 epoch 约束不被放宽。 */
class IdeInputsTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Test
  void directoryAllowsAbsenceButRejectsBlankOversizeAndControlCharacters() {
    assertNull(IdeInputs.requireDirectory(null));
    assertEquals("/workspace", IdeInputs.requireDirectory("/workspace"));
    assertValidation(
        "directory must not be blank when present", () -> IdeInputs.requireDirectory(" "));
    assertValidation(
        "directory exceeds 4096 characters",
        () -> IdeInputs.requireDirectory("/" + "a".repeat(4096)));
    assertValidation(
        "directory must not contain control characters", () -> IdeInputs.requireDirectory("/a\tb"));
  }

  @Test
  void epochAndViewIdKeepTheirValidationErrors() {
    assertEquals(7L, IdeInputs.requireEpoch(7L));
    assertValidation("bindingEpoch must be positive", () -> IdeInputs.requireEpoch(null));
    assertValidation("bindingEpoch must be positive", () -> IdeInputs.requireEpoch(0L));

    assertEquals(
        "viewId must be a canonical UUID",
        assertThrows(DomainException.class, () -> IdeInputs.requireViewId("bad")).getMessage());
    assertEquals(
        "viewId must be a canonical UUID",
        assertThrows(DomainException.class, () -> IdeInputs.requireViewId(null)).getMessage());
  }

  @Test
  void optionalInstanceAcceptsAbsenceAndRequiresCanonicalForm() {
    assertNull(IdeInputs.optionalInstance(null));
    assertEquals(
        IDENTITY.daemonInstanceId(), IdeInputs.optionalInstance(IDENTITY.daemonInstanceId()));
    assertEquals(
        "expectedDaemonInstanceId must not be blank",
        assertThrows(DomainException.class, () -> IdeInputs.optionalInstance(" ")).getMessage());
    assertEquals(
        "expectedDaemonInstanceId must be a canonical UUID",
        assertThrows(DomainException.class, () -> IdeInputs.optionalInstance("nope")).getMessage());
  }

  @Test
  void requiredCurrentInstanceReportsMissingFormAndReplacement() {
    assertEquals(
        IDENTITY.daemonInstanceId(),
        IdeInputs.requireCurrentInstance(
            IDENTITY.daemonInstanceId(), "expected is required", IDENTITY));

    assertValidation(
        "expected is required",
        () -> IdeInputs.requireCurrentInstance(null, "expected is required", IDENTITY));
    assertValidation(
        "expectedDaemonInstanceId must be a canonical UUID",
        () -> IdeInputs.requireCurrentInstance("nope", "expected is required", IDENTITY));

    DomainException replaced =
        assertThrows(
            DomainException.class,
            () ->
                IdeInputs.requireCurrentInstance(
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "expected is required", IDENTITY));
    assertEquals("VIEW_GONE", replaced.reason());
    assertEquals("environment daemon instance has been replaced", replaced.getMessage());
  }

  @Test
  void openEpochDistinguishesCreateFromResume() {
    assertEquals(0L, IdeInputs.requireOpenEpoch(EnvironmentIdeOpenMode.CREATE, 0L));
    assertEquals(3L, IdeInputs.requireOpenEpoch(EnvironmentIdeOpenMode.RESUME, 3L));

    assertValidation(
        "expectedBindingEpoch is required",
        () -> IdeInputs.requireOpenEpoch(EnvironmentIdeOpenMode.CREATE, null));
    assertValidation(
        "create requires expectedBindingEpoch 0",
        () -> IdeInputs.requireOpenEpoch(EnvironmentIdeOpenMode.CREATE, 1L));
    assertValidation(
        "resume requires a positive expectedBindingEpoch",
        () -> IdeInputs.requireOpenEpoch(EnvironmentIdeOpenMode.RESUME, 0L));
    // 缺失 epoch 的统一错误先于 mode 细分判定，与拆分前一致。
    assertValidation(
        "expectedBindingEpoch is required",
        () -> IdeInputs.requireOpenEpoch(EnvironmentIdeOpenMode.RESUME, null));
  }

  @Test
  void protocolPathIsRequiredInDaemonReplies() {
    assertEquals("/ws", IdeInputs.requireProtocolPath("/ws", "workspacePath"));
    assertEquals(
        "daemon did not return workspacePath",
        assertThrows(
                DomainException.class, () -> IdeInputs.requireProtocolPath(" ", "workspacePath"))
            .getMessage());
  }

  private static void assertValidation(String message, Executable call) {
    DomainException error = assertThrows(DomainException.class, call);
    assertEquals("environment", error.resource());
    assertEquals(message, error.getMessage());
  }
}
