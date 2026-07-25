package fun.fengwk.kkstudio.core.harness.redis;

import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 基于 Redis Pub/Sub channel {@code kk-studio:harness:signal} 的 activation notifier。
 *
 * <p>消息体是 {@link ExecutionTargetJsonCodec} 编码的 deterministic JSON（{@code {targetKind,
 * targetId}}）。任何 Redis 异常向上抛，由 Runtime 调用方隔离；本实现不吞异常、不重试、不缓存目标。
 */
public class RedisActivationNotifier implements ActivationNotifier {

  private final Supplier<StringRedisTemplate> stringRedisTemplateSupplier;
  private final String signalChannel;
  private final ExecutionTargetJsonCodec targetCodec;

  public RedisActivationNotifier(
      StringRedisTemplate stringRedisTemplate,
      HarnessRedisProperties properties,
      ExecutionTargetJsonCodec targetCodec) {
    this(
        () -> Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate"),
        properties,
        targetCodec);
  }

  RedisActivationNotifier(
      Supplier<StringRedisTemplate> stringRedisTemplateSupplier,
      HarnessRedisProperties properties,
      ExecutionTargetJsonCodec targetCodec) {
    this.stringRedisTemplateSupplier =
        Objects.requireNonNull(stringRedisTemplateSupplier, "stringRedisTemplateSupplier");
    this.signalChannel = Objects.requireNonNull(properties, "properties").requireSignalChannel();
    this.targetCodec = Objects.requireNonNull(targetCodec, "targetCodec");
  }

  @Override
  public void notifyAfterCommit(ExecutionTarget target) {
    String message = targetCodec.encode(target);
    StringRedisTemplate stringRedisTemplate = stringRedisTemplateSupplier.get();
    if (stringRedisTemplate == null) {
      throw new IllegalStateException("StringRedisTemplate is unavailable");
    }
    stringRedisTemplate.convertAndSend(signalChannel, message);
  }
}
