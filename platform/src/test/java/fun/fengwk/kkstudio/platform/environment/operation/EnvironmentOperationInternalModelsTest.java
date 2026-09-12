package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 验证内部领域模型与命令对象的安全边界： 绝不在 toString()、日志或异常中泄露 arguments、leaseToken 等敏感数据。 */
class EnvironmentOperationInternalModelsTest {

  /** 测试意图：验证 SweptOperationInfo 在 toString() 中脱敏 leaseToken，防止日志泄露。 */
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
    assertTrue(str.contains("leaseToken=***"));
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
}
