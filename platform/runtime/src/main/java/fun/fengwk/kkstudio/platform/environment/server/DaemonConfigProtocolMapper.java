package fun.fengwk.kkstudio.platform.environment.server;

import fun.fengwk.kkstudio.platform.daemon.protocol.DaemonConfiguration;
import fun.fengwk.kkstudio.platform.daemon.protocol.LspConfiguration;
import fun.fengwk.kkstudio.platform.daemon.protocol.LspServer;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonLspConfiguration;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonLspServerConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 把持久化卡片配置转换为冻结的控制协议类型；repeated LSP 服务器保持原 LinkedHashMap 声明顺序。 */
public final class DaemonConfigProtocolMapper {

  private DaemonConfigProtocolMapper() {}

  public static DaemonConfiguration toProtocol(DaemonConfigDTO config) {
    Objects.requireNonNull(config, "config");
    DaemonConfiguration.Builder builder =
        DaemonConfiguration.newBuilder().setStudioUrl(config.getStudioUrl());
    if (config.getBashExecutable() != null) {
      builder.setBashExecutable(config.getBashExecutable());
    }
    DaemonLspConfiguration lsp = config.getLsp();
    if (lsp != null) {
      LspConfiguration.Builder lspBuilder = LspConfiguration.newBuilder();
      for (Map.Entry<String, DaemonLspServerConfiguration> entry :
          lsp.getServers().entrySet()) {
        DaemonLspServerConfiguration server = entry.getValue();
        lspBuilder.addServers(
            LspServer.newBuilder()
                .setId(entry.getKey())
                .addAllCommand(list(server.getCommand()))
                .addAllExtensions(list(server.getExtensions()))
                .addAllRootMarkers(list(server.getRootMarkers()))
                .addAllFirstMatchMarkers(list(server.getFirstMatchMarkers()))
                .build());
      }
      builder.setLsp(lspBuilder.build());
    }
    if (config.getProxyUrl() != null) {
      builder.setProxyUrl(config.getProxyUrl());
    }
    return builder.build();
  }

  private static List<String> list(List<String> values) {
    return values == null ? List.of() : values;
  }
}
