package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** PUBLISH 走固定 channel；Redis 失败必须吞掉，不能冒泡到 PUT。 */
class RedisSystemSettingsChangePublisherTest {

  @Test
  void publishesEmptyPayloadOnConfiguredChannel() {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    RedisSystemSettingsChangePublisher publisher =
        new RedisSystemSettingsChangePublisher(template, "kk-studio:system-settings:test");

    publisher.publish();

    verify(template)
        .convertAndSend(
            "kk-studio:system-settings:test", RedisSystemSettingsChangePublisher.PAYLOAD);
  }

  @Test
  void redisFailureDoesNotPropagate() {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.convertAndSend(anyString(), anyString()))
        .thenThrow(new RuntimeException("redis down"));
    RedisSystemSettingsChangePublisher publisher =
        new RedisSystemSettingsChangePublisher(template, RedisSystemSettingsChangeListener.CHANNEL);

    assertDoesNotThrow(publisher::publish);
  }

  @Test
  void rejectsBlankChannel() {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    assertThrows(
        IllegalArgumentException.class,
        () -> new RedisSystemSettingsChangePublisher(template, " "));
  }
}
