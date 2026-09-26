#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <jar-command> <application-jar> <agent-jar-output>" >&2
  exit 2
fi

jar_command=$1
archive_dir=$(cd "$(dirname "$2")" && pwd -P)
archive="$archive_dir/$(basename "$2")"
output_parent=$(dirname "$3")
output_name=$(basename "$3")

agent_entry=$("$jar_command" tf "$archive" | grep -E '^BOOT-INF/lib/convention4j-agent-[^/]+[.]jar$' || true)
if [ -z "$agent_entry" ] || [ "$(printf '%s\n' "$agent_entry" | wc -l)" -ne 1 ]; then
  echo "Expected exactly one convention4j-agent dependency in the application JAR" >&2
  exit 1
fi

mkdir -p "$output_parent"
output_dir=$(cd "$output_parent" && pwd -P)
output="$output_dir/$output_name"
agent_filename=${agent_entry##*/}
agent_path="$output_dir/$agent_filename"

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
(cd "$work_dir" && "$jar_command" xf "$archive" "$agent_entry")
test -s "$work_dir/$agent_entry"
mv -f "$work_dir/$agent_entry" "$agent_path"

# The agent manifest adds its versioned filename to Boot-Class-Path so transformed
# JDK executor classes can resolve TTL classes. Keep that file and expose a stable alias.
if [ "$output" != "$agent_path" ]; then
  alias_tmp="$output_dir/.$output_name.tmp.$$"
  rm -f "$alias_tmp"
  ln -s "$agent_filename" "$alias_tmp"
  mv -f "$alias_tmp" "$output"
fi

for stale in "$output_dir"/convention4j-agent-*.jar; do
  if [ -e "$stale" ] && [ "$stale" != "$agent_path" ]; then
    rm -f "$stale"
  fi
done
