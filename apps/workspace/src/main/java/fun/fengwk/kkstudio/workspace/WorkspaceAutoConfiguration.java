package fun.fengwk.kkstudio.workspace;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Installs the workspace product services and their JDBC ownership adapter. */
@Configuration
@ComponentScan(basePackageClasses = WorkspaceAutoConfiguration.class)
public class WorkspaceAutoConfiguration {}
