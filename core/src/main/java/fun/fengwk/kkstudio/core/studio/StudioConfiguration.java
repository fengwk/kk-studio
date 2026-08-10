package fun.fengwk.kkstudio.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionConfigCodec;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionModelRegistry;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandDedupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasGroupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;

/** 装配持久的 Canvas command/query 端口。 */
@Configuration
public class StudioConfiguration {

  @Bean
  public DurableCanvasService durableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasGroupMapper groupMapper,
      CanvasNodeMapper nodeMapper,
      CanvasResourceMapper resourceMapper,
      CanvasNodeResourceMapper nodeResourceMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandDedupMapper commandDedupMapper,
      CanvasResourceRepository resourceRepository,
      CanvasFunctionRunRepository functionRunRepository,
      CanvasFunctionConfigCodec functionConfigCodec,
      CanvasFunctionModelRegistry functionModelRegistry,
      ObjectMapper objectMapper,
      PostgresqlSequenceIdGenerator idGenerator) {
    return new DurableCanvasService(
        documentMapper,
        groupMapper,
        nodeMapper,
        resourceMapper,
        nodeResourceMapper,
        linkMapper,
        commandDedupMapper,
        resourceRepository,
        functionRunRepository,
        functionConfigCodec,
        functionModelRegistry,
        objectMapper,
        idGenerator);
  }
}
