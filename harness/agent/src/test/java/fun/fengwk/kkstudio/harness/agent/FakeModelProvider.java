package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;

import java.util.ArrayList;
import java.util.List;

/** 可声明流事件、完成和失败顺序的 Provider 测试替身。 */
final class FakeModelProvider implements ModelProvider {

  private final List<Step> steps;
  private final FakeStream stream = new FakeStream();
  private ProviderRequest request;

  private FakeModelProvider(List<Step> steps) {
    this.steps = List.copyOf(steps);
  }

  static Sequence sequence() {
    return new Sequence();
  }

  ProviderRequest request() {
    return request;
  }

  FakeStream stream() {
    return stream;
  }

  @Override
  public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    this.request = request;
    for (Step step : steps) {
      step.deliver(handler, stream);
    }
    return stream;
  }

  static final class Sequence {

    private final List<Step> steps = new ArrayList<>();

    Sequence delta(ProviderStreamEvent event) {
      steps.add((handler, stream) -> handler.onEvent(event, stream));
      return this;
    }

    Sequence complete(ProviderResponse response) {
      steps.add((handler, stream) -> handler.onComplete(response, stream));
      return this;
    }

    Sequence fail(ProviderException error) {
      steps.add((handler, stream) -> handler.onError(error, stream));
      return this;
    }

    FakeModelProvider build() {
      return new FakeModelProvider(steps);
    }
  }

  @FunctionalInterface
  private interface Step {
    void deliver(ProviderStreamHandler handler, ProviderStream stream);
  }

  static final class FakeStream implements ProviderStream {

    private boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }
}
