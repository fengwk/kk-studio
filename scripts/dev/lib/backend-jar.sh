#!/usr/bin/env bash

backend_jar_path() {
  local root=$1 pom
  # Same root POM version anchor as docker-publish.yml, without needing Node at startup.
  local coordinates='<artifactId>kk-studio-parent</artifactId>[[:space:]]*<version>([^<]+)</version>'
  pom=$(<"$root/pom.xml") || return 1
  if [[ ! $pom =~ $coordinates ]]; then
    printf 'ERROR: root POM version not found in %s\n' "$root/pom.xml" >&2
    return 1
  fi
  local version=${BASH_REMATCH[1]}
  printf '%s\n' "$root/web/target/kk-studio-web-$version.jar"
}
