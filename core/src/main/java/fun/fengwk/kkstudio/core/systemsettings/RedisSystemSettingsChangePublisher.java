package fun.fengwk.kkstudio.core.systemsettings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;

/** 向固定 channel PUBLISH 一条空 payload 唤醒。Redis 失败只记警告，不向上传播。 */
@Slf4j
public final class RedisSystemSettingsChangePublisher implements SystemSettingsChangePublisher {

  /** 唤醒消息无语义，订阅方忽略内容并回读数据库。 */
  static final String PAYLOAD = "";

  private final StringRedisTemplate stringRedisTemplate;
  private final String channel;

  public RedisSystemSettingsChangePublisher(
      StringRedisTemplate stringRedisTemplate, String channel) {
    this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate");
    this.channel = requireChannel(channel);
  }

  @Override
  public void publish() {
    try {
      stringRedisTemplate.convertAndSend(channel, PAYLOAD);
    } catch (RuntimeException error) {
      log.warn("cannot publish system settings change hint on channel={}", channel, error);
    }
  }

  static String requireChannel(String channel) {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("channel must not be blank");
    }
    return channel;
  }
}
