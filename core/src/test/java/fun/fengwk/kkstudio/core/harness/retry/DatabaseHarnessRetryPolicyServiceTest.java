package fun.fengwk.kkstudio.core.harness.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.retry.mapper.HarnessRetryPolicyMapper;
import fun.fengwk.kkstudio.share.model.HarnessRetryPolicyDTO;

/** 单例 retry policy 的持久化不变量、全量替换与输入边界。 */
class DatabaseHarnessRetryPolicyServiceTest {

  @Test
  void rejectsMissingSingletonPolicyInsteadOfMaskingSchemaSeedFailure() {
    HarnessRetryPolicyMapper mapper = mock(HarnessRetryPolicyMapper.class);
    DatabaseHarnessRetryPolicyService service = new DatabaseHarnessRetryPolicyService(mapper);

    assertThrows(IllegalStateException.class, service::getRetryPolicy);
  }

  @Test
  void upsertsACompleteValidPolicy() {
    HarnessRetryPolicyMapper mapper = mock(HarnessRetryPolicyMapper.class);
    when(mapper.upsert(anyInt(), anyString(), anyLong(), anyLong())).thenReturn(1);
    DatabaseHarnessRetryPolicyService service = new DatabaseHarnessRetryPolicyService(mapper);

    HarnessRetryPolicyDTO saved = service.updateRetryPolicy(policy(2, "FIXED", 3_000L, 3_000L));

    assertEquals(2, saved.getMaxRetries());
    assertEquals("FIXED", saved.getBackoffStrategy());
    assertEquals(3_000L, saved.getBaseDelayMillis());
    verify(mapper).upsert(2, "FIXED", 3_000L, 3_000L);
  }

  @Test
  void validatesAllPublicConfigurationBoundsBeforeWriting() {
    HarnessRetryPolicyMapper mapper = mock(HarnessRetryPolicyMapper.class);
    DatabaseHarnessRetryPolicyService service = new DatabaseHarnessRetryPolicyService(mapper);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateRetryPolicy(policy(11, "FIXED", 1_000L, 1_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateRetryPolicy(policy(1, "FIXED", 999L, 1_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateRetryPolicy(policy(1, "EXPONENTIAL", 2_000L, 1_000L)));
    assertThrows(IllegalArgumentException.class, () -> service.updateRetryPolicy(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateRetryPolicy(policy(1, "unknown", 1_000L, 1_000L)));
  }

  @Test
  void replacesAnExistingSingletonRowThroughUpsert() {
    HarnessRetryPolicyMapper mapper = mock(HarnessRetryPolicyMapper.class);
    when(mapper.upsert(anyInt(), anyString(), anyLong(), anyLong())).thenReturn(1);
    DatabaseHarnessRetryPolicyService service = new DatabaseHarnessRetryPolicyService(mapper);

    service.updateRetryPolicy(policy(1, "EXPONENTIAL", 1_000L, 8_000L));

    verify(mapper).upsert(eq(1), eq("EXPONENTIAL"), eq(1_000L), eq(8_000L));
  }

  private static HarnessRetryPolicyDTO policy(
      int maxRetries, String strategy, long baseDelayMillis, long maxDelayMillis) {
    HarnessRetryPolicyDTO policy = new HarnessRetryPolicyDTO();
    policy.setMaxRetries(maxRetries);
    policy.setBackoffStrategy(strategy);
    policy.setBaseDelayMillis(baseDelayMillis);
    policy.setMaxDelayMillis(maxDelayMillis);
    return policy;
  }
}
