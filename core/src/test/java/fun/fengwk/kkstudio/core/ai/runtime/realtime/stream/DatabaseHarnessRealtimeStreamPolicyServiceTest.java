package fun.fengwk.kkstudio.core.ai.runtime.realtime.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.realtime.stream.mapper.HarnessRealtimeStreamPolicyMapper;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessRealtimeStreamPolicyDTO;

/** 全局 realtime Stream 策略必须 fail-fast、完整替换，并避免每次解析重复读取数据库。 */
class DatabaseHarnessRealtimeStreamPolicyServiceTest {

  @Test
  void rejectsMissingSingletonPolicyInsteadOfMaskingSchemaSeedFailure() {
    HarnessRealtimeStreamPolicyMapper mapper = mock(HarnessRealtimeStreamPolicyMapper.class);
    DatabaseHarnessRealtimeStreamPolicyService service =
        new DatabaseHarnessRealtimeStreamPolicyService(mapper);

    assertThrows(IllegalStateException.class, service::resolveMaxLength);
  }

  @Test
  void resolvesAndCachesTheSeededPolicy() {
    HarnessRealtimeStreamPolicyMapper mapper = mock(HarnessRealtimeStreamPolicyMapper.class);
    when(mapper.find()).thenReturn(row(5_000L));
    DatabaseHarnessRealtimeStreamPolicyService service =
        new DatabaseHarnessRealtimeStreamPolicyService(mapper);

    assertEquals(5_000L, service.resolveMaxLength());
    assertEquals(5_000L, service.getPolicy().getMaxLength());
    verify(mapper).find();
  }

  @Test
  void validatesAndUpsertsTheCompletePolicyAndRefreshesLocalCache() {
    HarnessRealtimeStreamPolicyMapper mapper = mock(HarnessRealtimeStreamPolicyMapper.class);
    when(mapper.upsert(anyLong())).thenReturn(1);
    DatabaseHarnessRealtimeStreamPolicyService service =
        new DatabaseHarnessRealtimeStreamPolicyService(mapper);

    HarnessRealtimeStreamPolicyDTO saved = service.updatePolicy(policy(100_000L));

    assertEquals(100_000L, saved.getMaxLength());
    assertEquals(100_000L, service.resolveMaxLength());
    verify(mapper).upsert(100_000L);
  }

  @Test
  void rejectsMissingOrNonPositiveMaxLengthBeforeWriting() {
    HarnessRealtimeStreamPolicyMapper mapper = mock(HarnessRealtimeStreamPolicyMapper.class);
    DatabaseHarnessRealtimeStreamPolicyService service =
        new DatabaseHarnessRealtimeStreamPolicyService(mapper);

    assertThrows(IllegalArgumentException.class, () -> service.updatePolicy(null));
    assertThrows(IllegalArgumentException.class, () -> service.updatePolicy(policy(null)));
    assertThrows(IllegalArgumentException.class, () -> service.updatePolicy(policy(0L)));
    assertThrows(IllegalArgumentException.class, () -> service.updatePolicy(policy(-1L)));
  }

  private static HarnessRealtimeStreamPolicyDO row(long maxLength) {
    HarnessRealtimeStreamPolicyDO row = new HarnessRealtimeStreamPolicyDO();
    row.setId(1);
    row.setMaxLength(maxLength);
    return row;
  }

  private static HarnessRealtimeStreamPolicyDTO policy(Long maxLength) {
    HarnessRealtimeStreamPolicyDTO dto = new HarnessRealtimeStreamPolicyDTO();
    dto.setMaxLength(maxLength);
    return dto;
  }
}
