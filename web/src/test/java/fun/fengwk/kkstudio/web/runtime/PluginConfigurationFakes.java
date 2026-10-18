package fun.fengwk.kkstudio.web.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.util.Arrays;

/** 不接触数据库的组合根测试使用的插件配置仓储替身：每个给定 Plugin 一行 revision 1 的空配置（由 schema 补齐默认值）。 */
final class PluginConfigurationFakes {

  private PluginConfigurationFakes() {}

  static PluginConfigurationRepository repository(String... pluginIds) {
    PluginConfigurationRepository repository = mock(PluginConfigurationRepository.class);
    when(repository.findAll())
        .thenReturn(
            Arrays.stream(pluginIds)
                .map(pluginId -> new PluginConfigurationRow(pluginId, 1L, "{}"))
                .toList());
    return repository;
  }
}
