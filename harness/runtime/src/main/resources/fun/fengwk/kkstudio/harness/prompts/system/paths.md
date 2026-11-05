[paths]
{{#hasAbsolute}}- Absolute paths are files on the environment's machine. Use them with {{{absoluteTools}}}.
{{/hasAbsolute}}{{#hasVfs}}- vfs:/ paths are files stored by the platform. Use them with {{{vfsTools}}}.{{#hasBash}} `bash` cannot see them.{{/hasBash}}{{#canWrite}} Only session_dir is writable.{{/canWrite}}
{{/hasVfs}}
