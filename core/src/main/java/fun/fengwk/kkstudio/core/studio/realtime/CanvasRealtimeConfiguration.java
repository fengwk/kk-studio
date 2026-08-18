package fun.fengwk.kkstudio.core.studio.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;

import java.util.Objects;

/** Canvas realtime 投影装配：bounded Redis Stream patch 缓存。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanvasRealtimeProperties.class)
public class CanvasRealtimeConfiguration {

  @Bean
  public RedisCanvasPatchStore canvasPatchStore(
      StringRedisTemplate stringRedisTemplate,
      CanvasRealtimeProperties properties,
      SystemSettingsSnapshot snapshot,
      ObjectMapper objectMapper) {
    return new RedisCanvasPatchStore(
        Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate"),
        Objects.requireNonNull(properties, "properties"),
        Objects.requireNonNull(snapshot, "snapshot").get().advanced(),
        new CanvasPatchJsonCodec(Objects.requireNonNull(objectMapper, "objectMapper")));
  }
}
