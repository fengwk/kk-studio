package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.STEP_LIMIT;

import org.junit.jupiter.api.AfterEach;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * ThreadProcessor 测试基类：fixture 生命周期管理（每个测试一个 Fixture，scheduler 随测试结束关闭）与公共小工具。
 *
 * <p>具体行为测试按主题拆分在 {@link ThreadProcessorModelTest}（terminal Model apply）、{@link
 * ThreadProcessorToolBatchTest}（Tool sibling 原子 batch）、{@link
 * ThreadProcessorPlanningTest}（continuation / input / CAS / reschedule）、{@link
 * ThreadProcessorClaimTest}（claim 与 admission）与 {@link ThreadProcessorNormalizationTest}（history
 * normalization）中，全部复用 {@link ThreadProcessorTestSupport}。
 */
abstract class ThreadProcessorTestBase {

  private final List<Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    fixtures.forEach(Fixture::close);
  }

  protected final Fixture fixture() {
    return fixture(STEP_LIMIT);
  }

  protected final Fixture fixture(int stepLimit) {
    return fixture(stepLimit, null);
  }

  /** {@code processorStore} 非空时 processor 使用包装 store（seed / 断言仍用 {@code fixture.store}）。 */
  protected final Fixture fixture(int stepLimit, HarnessStore processorStore) {
    Fixture fixture = new Fixture(stepLimit, processorStore);
    fixtures.add(fixture);
    return fixture;
  }

  protected static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  protected static <T> T inTx(Fixture fixture, Function<HarnessStore.Transaction, T> body) {
    return fixture.store.transaction(body);
  }
}
