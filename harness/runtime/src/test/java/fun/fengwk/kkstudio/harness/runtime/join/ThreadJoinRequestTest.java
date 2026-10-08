package fun.fengwk.kkstudio.harness.runtime.join;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** ThreadJoinRequest 准入不可变记录与校验规则测试。 */
class ThreadJoinRequestTest {

  private static final String VALID_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void acceptsValidTaskJoinRequest() {
    // 测试意图：验证带 parentThreadId 和 expectedParentHeadEntryId 的合法 task join 请求能完整保存并暴露全部属性。
    UUID invocationId = id(1);
    UUID parentThreadId = id(2);
    UUID headEntryId = id(3);
    ThreadJoinRequest request =
        new ThreadJoinRequest(
            invocationId,
            parentThreadId,
            headEntryId,
            VALID_HASH,
            "test-agent",
            10,
            4,
            8,
            16,
            JoinPurpose.TASK);

    assertEquals(invocationId, request.invocationId());
    assertEquals(parentThreadId, request.parentThreadId());
    assertEquals(headEntryId, request.expectedParentHeadEntryId());
    assertEquals(VALID_HASH, request.requestHash());
    assertEquals("test-agent", request.agent());
    assertEquals(10, request.maxTurns());
    assertEquals(4, request.maxDepth());
    assertEquals(8, request.maxConcurrentChildren());
    assertEquals(16, request.maxConcurrentThreads());
  }

  @Test
  void acceptsValidRootTicketRequest() {
    // 测试意图：验证 parentThreadId 与 expectedParentHeadEntryId 均为 null 且 maxTurns 为 null 的根 ticket 请求合法性。
    UUID invocationId = id(10);
    ThreadJoinRequest request =
        new ThreadJoinRequest(
            invocationId, null, null, VALID_HASH, "root-agent", null, 1, 1, 1, JoinPurpose.TASK);

    assertEquals(invocationId, request.invocationId());
    assertNull(request.parentThreadId());
    assertNull(request.expectedParentHeadEntryId());
    assertEquals(VALID_HASH, request.requestHash());
    assertEquals("root-agent", request.agent());
    assertNull(request.maxTurns());
    assertEquals(1, request.maxDepth());
    assertEquals(1, request.maxConcurrentChildren());
    assertEquals(1, request.maxConcurrentThreads());
  }

  @Test
  void acceptsBoundaryAgentLength() {
    // 测试意图：验证 agent 边界长度（长度 1 以及最大允许长度 256）能成功构造。
    String singleCharAgent = "a";
    ThreadJoinRequest req1 =
        new ThreadJoinRequest(
            id(1), null, null, VALID_HASH, singleCharAgent, null, 1, 1, 1, JoinPurpose.TASK);
    assertEquals(singleCharAgent, req1.agent());

    String maxLenAgent = "a".repeat(256);
    ThreadJoinRequest req2 =
        new ThreadJoinRequest(
            id(2), null, null, VALID_HASH, maxLenAgent, null, 1, 1, 1, JoinPurpose.TASK);
    assertEquals(maxLenAgent, req2.agent());
  }

  @Test
  void rejectsNullInvocationId() {
    // 测试意图：验证 invocationId 为 null 时抛出 NullPointerException。
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoinRequest(
                null, id(2), id(3), VALID_HASH, "agent", 10, 1, 1, 1, JoinPurpose.TASK));
  }

  @Test
  void rejectsInconsistentParentAndHead() {
    // 测试意图：验证 parentThreadId 与 expectedParentHeadEntryId 的配对完整性约束（Task join 必须有 head，Root ticket
    // 不能有 head）。
    // parent 非空但 head 为 null
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ThreadJoinRequest(
                    id(1), id(2), null, VALID_HASH, "agent", 10, 1, 1, 1, JoinPurpose.TASK));
    assertEquals("parent head is required for task join", ex1.getMessage());

    // parent 为 null 但 head 非空
    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ThreadJoinRequest(
                    id(1), null, id(3), VALID_HASH, "agent", 10, 1, 1, 1, JoinPurpose.TASK));
    assertEquals("root ticket has no parent head", ex2.getMessage());
  }

  @Test
  void rejectsInvalidRequestHash() {
    // 测试意图：验证 requestHash 校验（null、大写、长度不足、长度超长、非法十六进制字符）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, null, "agent", null, 1, 1, 1, JoinPurpose.TASK));

    // 包含大写字符
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1),
                null,
                null,
                "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef",
                "agent",
                null,
                1,
                1,
                1,
                JoinPurpose.TASK));

    // 长度不足 64
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, "0123456789abcdef", "agent", null, 1, 1, 1, JoinPurpose.TASK));

    // 长度超过 64
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH + "a", "agent", null, 1, 1, 1, JoinPurpose.TASK));

    // 非十六进制字符
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, "g".repeat(64), "agent", null, 1, 1, 1, JoinPurpose.TASK));
  }

  @Test
  void rejectsInvalidAgent() {
    // 测试意图：验证 agent 字段非空、非空白字符且长度不超过 256 的约束。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, null, null, 1, 1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "", null, 1, 1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "   ", null, 1, 1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "a".repeat(257), null, 1, 1, 1, JoinPurpose.TASK));
  }

  @Test
  void rejectsInvalidMaxTurns() {
    // 测试意图：验证 maxTurns 非空时必须为正整数（<= 0 拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", 0, 1, 1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", -1, 1, 1, 1, JoinPurpose.TASK));
  }

  @Test
  void rejectsInvalidAdmissionLimits() {
    // 测试意图：验证 maxDepth、maxConcurrentChildren 与 maxConcurrentTreeJoins 必须全部大于等于 1。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", null, 0, 1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", null, -1, 1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", null, 1, 0, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", null, 1, -1, 1, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", null, 1, 1, 0, JoinPurpose.TASK));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoinRequest(
                id(1), null, null, VALID_HASH, "agent", null, 1, 1, -1, JoinPurpose.TASK));
  }

  @Test
  void equalsAndHashCodeAndToStringContract() {
    // 测试意图：验证 Record 自动生成的 equals、hashCode 与 toString 符合规范。
    ThreadJoinRequest req1 =
        new ThreadJoinRequest(
            id(1), id(2), id(3), VALID_HASH, "agent", 10, 2, 4, 8, JoinPurpose.TASK);
    ThreadJoinRequest req2 =
        new ThreadJoinRequest(
            id(1), id(2), id(3), VALID_HASH, "agent", 10, 2, 4, 8, JoinPurpose.TASK);
    ThreadJoinRequest diff =
        new ThreadJoinRequest(
            id(9), id(2), id(3), VALID_HASH, "agent", 10, 2, 4, 8, JoinPurpose.TASK);

    assertEquals(req1, req2);
    assertEquals(req1.hashCode(), req2.hashCode());
    assertNotEquals(req1, diff);
    assertNotEquals(req1, null);
    assertNotEquals(req1, "other");
    assertNotNull(req1.toString());
  }

  @Test
  void rejectsNullPurpose() {
    // 测试意图：purpose 是 join 契约必填事实（task/compaction），null 必须被拒。
    assertThrows(
        NullPointerException.class,
        () -> new ThreadJoinRequest(id(1), null, null, VALID_HASH, "agent", null, 1, 1, 1, null));
  }
}
