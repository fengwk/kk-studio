package fun.fengwk.kkstudio.harness.runtime.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** {@link ModelHttpErrorPolicy} 的白名单语义与输入边界。 */
class ModelHttpErrorPolicyTest {

  @Test
  void defaultWhitelistMatchesDocumentedDefault() {
    assertEquals(
        List.of(408, 429, 500, 502, 503, 504), ModelHttpErrorPolicy.DEFAULT.retryStatusCodes());
    assertEquals(
        ModelHttpErrorPolicy.DEFAULT_RETRY_STATUS_CODES,
        ModelHttpErrorPolicy.DEFAULT.retryStatusCodes());
  }

  @Test
  void allowsRetryOnlyForListedStatus() {
    ModelHttpErrorPolicy policy = new ModelHttpErrorPolicy(List.of(429, 503));

    assertTrue(policy.allowsRetry(429));
    assertTrue(policy.allowsRetry(503));
    assertFalse(policy.allowsRetry(500));
    assertFalse(policy.allowsRetry(401));
    assertFalse(policy.allowsRetry(301));
  }

  @Test
  void emptyWhitelistDisablesEveryHttpRetry() {
    ModelHttpErrorPolicy policy = new ModelHttpErrorPolicy(List.of());

    assertTrue(policy.retryStatusCodes().isEmpty());
    assertFalse(policy.allowsRetry(429));
    assertFalse(policy.allowsRetry(500));
  }

  @Test
  void copiesAndFreezesInputList() {
    List<Integer> mutable = new ArrayList<>(List.of(429));
    ModelHttpErrorPolicy policy = new ModelHttpErrorPolicy(mutable);
    mutable.add(500);

    assertEquals(List.of(429), policy.retryStatusCodes());
    assertThrows(UnsupportedOperationException.class, () -> policy.retryStatusCodes().add(500));
  }

  @Test
  void rejectsNullDuplicatesAndOutOfRangeStatuses() {
    assertThrows(NullPointerException.class, () -> new ModelHttpErrorPolicy(null));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelHttpErrorPolicy(Arrays.asList(429, null)));
    assertThrows(IllegalArgumentException.class, () -> new ModelHttpErrorPolicy(List.of(429, 429)));
    assertThrows(IllegalArgumentException.class, () -> new ModelHttpErrorPolicy(List.of(399)));
    assertThrows(IllegalArgumentException.class, () -> new ModelHttpErrorPolicy(List.of(600)));
    assertThrows(IllegalArgumentException.class, () -> new ModelHttpErrorPolicy(List.of(200)));
  }
}
