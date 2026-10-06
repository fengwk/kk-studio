package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;

import java.util.List;
import java.util.UUID;

/** 统一交互窄共用根解析 {@link InteractionRootResolver} 的单元测试。 */
class InteractionRootResolverTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionRootResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    resolver = new InteractionRootResolver(runtimes);
  }

  /** 测试意图：真实根只取 head-to-root 链末位——根自身即末位，后代取祖先链末位，来源自身与中间父节点都不被当作根。 */
  @Test
  void requireRootIdReturnsChainTailForRootAndDescendant() {
    when(runtime.findAncestorChain(id(1))).thenReturn(List.of(id(1)));
    when(runtime.findAncestorChain(id(2))).thenReturn(List.of(id(2), id(1)));

    assertEquals(id(1), resolver.requireRootId(id(1)));
    assertEquals(id(1), resolver.requireRootId(id(2)));
  }

  /** 测试意图：Thread 不存在（空链）时显式抛 not-found，绝不静默回退为自身。 */
  @Test
  void requireRootIdFailsExplicitlyWhenThreadDoesNotExist() {
    when(runtime.findAncestorChain(id(9))).thenReturn(List.of());

    assertThrows(HarnessRuntimeNotFoundException.class, () -> resolver.requireRootId(id(9)));
  }

  /** 测试意图：null threadId 直接 NPE；Runtime 未就绪时 requireRootId / requireRuntime 都确定性拒绝。 */
  @Test
  void requireRootIdRejectsNullThreadAndUnavailableRuntime() {
    assertThrows(NullPointerException.class, () -> resolver.requireRootId(null));

    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> resolver.requireRootId(id(1)));
    assertThrows(IllegalStateException.class, resolver::requireRuntime);
  }

  /** 测试意图：requireRuntime 暴露与根解析相同来源的 Runtime，查询 owner 解析复用同一访问口。 */
  @Test
  void requireRuntimeExposesTheSameRuntime() {
    assertSame(runtime, resolver.requireRuntime());
  }
}
