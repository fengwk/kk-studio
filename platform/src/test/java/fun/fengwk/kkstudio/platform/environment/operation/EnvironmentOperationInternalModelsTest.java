package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 验证内部领域模型与命令对象的安全边界： 绝不在 toString()、日志或异常中泄露 arguments、leaseToken 等敏感数据。 */
class EnvironmentOperationInternalModelsTest {

  /** 测试意图：验证 SweptOperationInfo 在 toString() 中完全省略 leaseToken 字段名与值。 */
  @Test
  void sweptOperationInfoDoesNotLeakLeaseTokenInToString() {
    UUID id = UUID.randomUUID();
    UUID ownerNodeId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();

    SweptOperationInfo info = new SweptOperationInfo(id, ownerNodeId, leaseToken);
    String str = info.toString();

    assertTrue(str.contains(id.toString()));
    assertTrue(str.contains(ownerNodeId.toString()));
    assertFalse(str.contains(leaseToken.toString()), "leaseToken 绝不能泄露在 toString 中");
    assertFalse(str.contains("leaseToken"), "leaseToken 字段名必须在 toString 中彻底省略");
  }

  /** 测试意图：验证 ClaimedOperation 构造时对非正数剩余超时 fail closed。 */
  @Test
  void claimedOperationFailsClosedOnNonPositiveTimeout() {
    UUID opId = UUID.randomUUID();
    EnvironmentOperation op =
        new EnvironmentOperation(
            opId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationStatus.RUNNING,
            1L,
            2L,
            "{}",
            "{}",
            Instant.now().plusSeconds(60),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());

    assertThrows(IllegalArgumentException.class, () -> new ClaimedOperation(op, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new ClaimedOperation(op, Duration.ofMillis(-100)));
  }

  /** 测试意图：验证 ClaimedOperation 在 toString() 中脱敏 arguments 和 leaseToken。 */
  @Test
  void claimedOperationDoesNotLeakSecretsInToString() {
    UUID opId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID ownerNodeId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    String secretArgs = "{\"token\":\"super_secret_token_123\"}";

    EnvironmentOperation op =
        new EnvironmentOperation(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationStatus.RUNNING,
            1L,
            2L,
            secretArgs,
            "{\"sourceType\":\"git\"}",
            Instant.now().plusSeconds(60),
            ownerNodeId,
            leaseToken,
            Instant.now(),
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());

    ClaimedOperation claimed = new ClaimedOperation(op, Duration.ofSeconds(30));
    String str = claimed.toString();

    assertTrue(str.contains(opId.toString()));
    assertFalse(str.contains("super_secret_token_123"), "arguments 绝不能泄露在 toString 中");
    assertFalse(str.contains(leaseToken.toString()), "leaseToken 绝不能泄露在 toString 中");
  }

  /** 测试意图：验证 CreatePendingOperationWithTimeoutCommand 在 toString() 中脱敏 arguments。 */
  @Test
  void createPendingOperationWithTimeoutCommandDoesNotLeakArgumentsInToString() {
    UUID opId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    String secretArgs = "{\"gitPassword\":\"my_password_xyz\"}";

    CreatePendingOperationWithTimeoutCommand cmd =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            1L,
            2L,
            secretArgs,
            "{\"type\":\"git\"}",
            30000L);

    String str = cmd.toString();
    assertTrue(str.contains(opId.toString()));
    assertFalse(str.contains("my_password_xyz"), "arguments 绝不能泄露在 toString 中");
  }

  /** 测试意图：验证 EnvironmentOperation 在 toString() 与 Jackson 序列化中均彻底排除 leaseToken 与 arguments。 */
  @Test
  void environmentOperationDoesNotLeakSecretsInToStringAndJson() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID ownerNodeId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    String secretArgs = "{\"privateKey\":\"super_secret_ssh_key\"}";

    EnvironmentOperation op =
        new EnvironmentOperation(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationStatus.RUNNING,
            1L,
            2L,
            secretArgs,
            "{\"type\":\"git\"}",
            Instant.now().plusSeconds(60),
            ownerNodeId,
            leaseToken,
            Instant.now(),
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());

    String str = op.toString();
    assertTrue(str.contains(opId.toString()));
    assertFalse(str.contains("super_secret_ssh_key"), "arguments 绝不能泄露在 toString 中");
    assertFalse(str.contains(leaseToken.toString()), "leaseToken 绝不能泄露在 toString 中");
    assertFalse(str.contains("leaseToken"), "leaseToken 字段名必须在 toString 中彻底省略");

    ObjectMapper mapper = new ObjectMapper();
    mapper.findAndRegisterModules();
    String json = mapper.writeValueAsString(op);
    assertFalse(json.contains("super_secret_ssh_key"), "arguments 绝不能被 Jackson 序列化");
    assertFalse(json.contains(leaseToken.toString()), "leaseToken 绝不能被 Jackson 序列化");
    assertFalse(json.contains("leaseToken"), "leaseToken 字段名绝不能出现在 JSON 中");
  }

  /** 测试意图：验证 DeadlineSweepResult.totalSwept 计算逻辑。 */
  @Test
  void deadlineSweepResultTotalSweptCalculatesSum() {
    DeadlineSweepResult result = new DeadlineSweepResult(3, 7);
    assertEquals(10, result.totalSwept());
  }

  /** 测试意图：验证 SafeEnvironmentOperation.isTerminal 委托状态机 terminal 判断。 */
  @Test
  void safeEnvironmentOperationIsTerminalReflectsStatus() {
    SafeEnvironmentOperation running =
        new SafeEnvironmentOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationStatus.RUNNING,
            1L,
            0L,
            "{}",
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());
    assertFalse(running.isTerminal());

    SafeEnvironmentOperation succeeded =
        new SafeEnvironmentOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationStatus.SUCCEEDED,
            1L,
            0L,
            "{}",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            "{}",
            null,
            null,
            Instant.now(),
            Instant.now());
    assertTrue(succeeded.isTerminal());
  }
}
