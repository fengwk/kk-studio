[environment]
{{#commandsAndFiles}}The environment is the machine where commands run and host files live. {{/commandsAndFiles}}{{#commandsOnly}}The environment is the machine where commands run. {{/commandsOnly}}{{#filesOnly}}The environment is the machine where host files live. {{/filesOnly}}The user can switch it during the session, so absolute paths in earlier messages may refer to another machine.{{^hasEnvironment}} No environment is selected now. Operations that require an Environment are not executed until one is selected. The `read` tool can still read `resource://` URIs.{{/hasEnvironment}}

<current_environment>
  <name>{{name}}</name>
  <description>{{description}}</description>
{{#hasOs}}  <os>{{os}}</os>
{{/hasOs}}{{#hasUser}}  <user>{{user}}</user>
{{/hasUser}}{{#hasHome}}  <home>{{home}}</home>
{{/hasHome}}{{#hasTempDir}}  <temp_dir>{{tempDir}}</temp_dir>
{{/hasTempDir}}</current_environment>
