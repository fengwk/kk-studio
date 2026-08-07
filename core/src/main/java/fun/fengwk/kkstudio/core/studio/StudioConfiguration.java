package fun.fengwk.kkstudio.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandDedupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;

/** 装配持久的 Canvas command/query 端口。 */
@Configuration
public class StudioConfiguration {

  @Bean
  public DurableCanvasService durableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandDedupMapper commandDedupMapper,
      ObjectMapper objectMapper,
      PostgresqlSequenceIdGenerator idGenerator) {
    return new DurableCanvasService(
        documentMapper, nodeMapper, linkMapper, commandDedupMapper, objectMapper, idGenerator);
  }

  @Bean
  public CanvasQueryService canvasQueryService(DurableCanvasService durableCanvasService) {
    return durableCanvasService;
  }

  @Bean
  public CanvasCommandService canvasCommandService(DurableCanvasService durableCanvasService) {
    return durableCanvasService;
  }
}
