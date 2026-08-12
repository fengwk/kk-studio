package fun.fengwk.kkstudio.core.studio.resource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Canvas 媒体集成装配：ffprobe/ffmpeg 二进制与缩略图参数。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanvasMediaProperties.class)
public class CanvasMediaConfiguration {}
