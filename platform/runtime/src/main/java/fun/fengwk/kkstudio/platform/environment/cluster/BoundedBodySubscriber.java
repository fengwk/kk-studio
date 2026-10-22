package fun.fengwk.kkstudio.platform.environment.cluster;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * 共用的小型有界响应体订阅者。
 *
 * <p>委托 {@link HttpResponse.BodySubscribers#ofByteArray()} 累积字节，同时按到达字节数真正计数；超过上限立即取消订阅并让
 * completion 异常完成。调用方仍须用总超时覆盖 body 读取，本类不提供无界读取路径。异常消息由调用方提供，避免网络层泄漏业务内容。
 */
public final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

  private final HttpResponse.BodySubscriber<byte[]> delegate =
      HttpResponse.BodySubscribers.ofByteArray();
  private final long maxBytes;
  private final Supplier<RuntimeException> overflow;
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private long received;
  private Flow.Subscription subscription;

  public BoundedBodySubscriber(long maxBytes, Supplier<RuntimeException> overflow) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    this.maxBytes = maxBytes;
    this.overflow = overflow;
    delegate
        .getBody()
        .whenComplete(
            (value, error) -> {
              if (error != null) {
                body.completeExceptionally(error);
              } else {
                body.complete(value);
              }
            });
  }

  public CompletableFuture<byte[]> bodyFuture() {
    return body;
  }

  @Override
  public CompletionStage<byte[]> getBody() {
    return body;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    delegate.onSubscribe(subscription);
  }

  @Override
  public void onNext(List<ByteBuffer> items) {
    for (ByteBuffer item : items) {
      received += item.remaining();
    }
    if (received > maxBytes) {
      cancel();
      body.completeExceptionally(overflow.get());
      return;
    }
    delegate.onNext(items);
  }

  @Override
  public void onError(Throwable throwable) {
    delegate.onError(throwable);
  }

  @Override
  public void onComplete() {
    delegate.onComplete();
  }

  public void cancel() {
    Flow.Subscription current = subscription;
    if (current != null) {
      current.cancel();
    }
  }
}
