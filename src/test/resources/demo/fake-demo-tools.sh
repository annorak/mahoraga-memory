#!/usr/bin/env bash
set -eu

readonly TOOL="${0##*/}"

answer() {
  if [[ "$(cat "$FAKE_MISMATCH")" == "$1" ]]; then
    printf 'wrong\n'
  else
    printf '%s\n' "$2"
  fi
}

fake_java() {
  if [[ "${1:-}" == "-version" ]]; then
    printf 'openjdk version "%s.0.1"\n' "$FAKE_JAVA_VERSION" >&2
    return 0
  fi
  [[ " $* " != *" --help "* ]] || return 0
  local role=unknown output="" generation arguments=" $* "
  if [[ " $* " == *" arm "* && " $* " == *"memory-off"* ]]; then
    role=control
  elif [[ " $* " == *" arm "* && " $* " == *"memory-on"* ]]; then
    role=memory
  elif [[ " $* " == *" compare "* ]]; then
    role=compare
  fi
  [[ "$role" != unknown ]] || return 8
  case "$role" in
    control)
      [[ "$arguments" == *" arm --synthetic-demo --mode memory-off --output "* ]] \
        || return 8
      ;;
    memory)
      [[ "$arguments" == *" arm --synthetic-demo --mode memory-on --output "* ]] \
        || return 8
      ;;
    compare)
      [[ "$arguments" == *" compare --control "*" --memory "*" --output "* ]] \
        || return 8
      ;;
  esac
  generation="$(cat "$FAKE_GENERATION")"
  printf 'java %s %s\n' "$role" "$generation" >> "$FAKE_EVENTS"
  if [[ -n "${FAKE_TAMPER:-}" ]]; then
    printf '%s' "$FAKE_TAMPER" > "$FAKE_MISMATCH"
    return 7
  fi
  if [[ -n "${FAKE_SIGNAL:-}" && "$role" == control ]]; then
    kill -s "$FAKE_SIGNAL" "$PPID"
    exec /bin/sleep 30
  fi
  [[ "${FAKE_JAVA_FAIL:-}" != "$role" ]] || return 7
  while (($#)); do
    if [[ "$1" == "--output" ]]; then
      output="$2"
      shift
    fi
    shift
  done
  [[ -n "$output" ]] || return 8
  if [[ "$role" == compare ]]; then
    cp "$FAKE_FINAL" "$output"
  else
    printf '{"arm":"%s"}' "$role" > "$output"
  fi
  if [[ "${FAKE_CHANGE_JAR:-}" == "$role" ]]; then
    printf changed >> "$FAKE_ROOT/target/mahoraga-memory-0.1.0-SNAPSHOT.jar"
  fi
  [[ "${FAKE_JAVA_FAIL_AFTER_OUTPUT:-}" != "$role" ]] || return 7
}

fake_image_inspect() {
  [[ "$#" -eq 5 && "$3" == "--format" && "$5" == "postgres:18.4-alpine" ]] \
    || return 4
  case "$4" in
    '{{.Id}}') printf 'fake-image-id\n' ;;
    '{{json .Config.Entrypoint}}') printf '["docker-entrypoint.sh"]\n' ;;
    '{{json .Config.Cmd}}') printf '["postgres"]\n' ;;
    *) return 4 ;;
  esac
}

fake_container_inspect() {
  local state generation target template
  state="$(cat "$FAKE_STATE")"
  generation="$(cat "$FAKE_GENERATION")"
  [[ "$state" != none && "$#" -eq 5 && "$3" == "--format" ]] || return 1
  template="$4"
  target="$5"
  [[ "$target" == "mahoraga-memory-demo"
      || "$target" == "fake-container-$generation" ]] || return 4
  case "$template" in
    '{{.Id}}') printf 'fake-container-%s\n' "$generation" ;;
    '{{.Name}}') answer name /mahoraga-memory-demo ;;
    *'.Config.Labels'*) answer label true ;;
    '{{.Config.Image}}') answer image-reference postgres:18.4-alpine ;;
    '{{.Image}}') answer image-identity fake-image-id ;;
    '{{len .HostConfig.PortBindings}}') answer port-count 1 ;;
    *'.HostConfig.PortBindings'*)
      answer binding '[{"HostIp":"127.0.0.1","HostPort":"55432"}]' ;;
    '{{len .HostConfig.Binds}}') answer binds 0 ;;
    '{{len .HostConfig.Mounts}}') answer mount-specifications 0 ;;
    '{{len .HostConfig.VolumesFrom}}') answer volumes-from 0 ;;
    '{{len .HostConfig.Tmpfs}}') answer tmpfs-count 1 ;;
    *'.HostConfig.Tmpfs'*)
      answer tmpfs-options 'rw,nosuid,nodev,noexec,size=536870912' ;;
    '{{.HostConfig.RestartPolicy.Name}}') answer restart no ;;
    '{{.HostConfig.RestartPolicy.MaximumRetryCount}}') answer restart-count 0 ;;
    '{{.HostConfig.AutoRemove}}') answer auto-remove false ;;
    '{{.HostConfig.NetworkMode}}') answer network bridge ;;
    '{{len .NetworkSettings.Networks}}') answer network-count 1 ;;
    *'.NetworkSettings.Networks'*) answer network-attached true ;;
    '{{json .Config.Entrypoint}}')
      answer entrypoint '["docker-entrypoint.sh"]' ;;
    '{{json .Config.Cmd}}') answer command '["postgres"]' ;;
    *'.Config.Env'*)
      if [[ "$(cat "$FAKE_MISMATCH")" == database-environment ]]; then
        printf 'POSTGRES_DB=wrong\n'
      else
        printf 'POSTGRES_DB=mahoraga_demo\n'
        printf 'POSTGRES_USER=mahoraga_demo\n'
        printf 'POSTGRES_PASSWORD=present\n'
      fi
      ;;
    *'range .Mounts'*)
      answer runtime-mounts 'tmpfs||/var/lib/postgresql|true' ;;
    '{{.State.Status}}')
      if [[ "$(cat "$FAKE_MISMATCH")" == state ]]; then
        printf 'paused\n'
      elif [[ "$state" == exited && "${FAKE_POST_STOP_STATE:-0}" == 1 ]]; then
        printf 'running\n'
      else
        printf '%s\n' "$state"
      fi
      ;;
    *) return 4 ;;
  esac
}

fake_run() {
  local state generation arguments
  state="$(cat "$FAKE_STATE")"
  [[ "$state" == none && "${FAKE_RUN_FAIL:-0}" != 1 ]] || return 9
  arguments=" $* "
  [[ "$arguments" == *" --detach "* ]]
  [[ "$arguments" == *" --pull=never "* ]]
  [[ "$arguments" == *" --name mahoraga-memory-demo "* ]]
  [[ "$arguments" == *" --label dev.mahoraga.memory.synthetic=true "* ]]
  [[ "$arguments" == *" --restart=no "* ]]
  [[ "$arguments" == *" --network=bridge "* ]]
  [[ "$arguments" == *" --publish 127.0.0.1:55432:5432 "* ]]
  [[ "$arguments" == *" --tmpfs /var/lib/postgresql:rw,nosuid,nodev,noexec,size=536870912 "* ]]
  [[ "$arguments" == *" --env POSTGRES_DB=mahoraga_demo "* ]]
  [[ "$arguments" == *" --env POSTGRES_USER=mahoraga_demo "* ]]
  [[ "$arguments" == *" --env POSTGRES_PASSWORD "* ]]
  [[ "$arguments" != *" --rm "* && "$arguments" != *" --volume "*
      && "$arguments" != *" -v "* && "$arguments" != *" --mount "*
      && "$arguments" != *" --entrypoint "* ]]
  [[ "${!#}" == "postgres:18.4-alpine" && -n "${POSTGRES_PASSWORD:-}" ]]
  generation=$(( $(cat "$FAKE_GENERATION") + 1 ))
  printf '%s' "$generation" > "$FAKE_GENERATION"
  printf running > "$FAKE_STATE"
  printf 'docker run %s\n' "$generation" >> "$FAKE_EVENTS"
  printf 'fake-container-%s\n' "$generation"
}

fake_docker() {
  local generation
  case "${1:-} ${2:-}" in
    "info --format") [[ "$FAKE_DOCKER_INFO" == up ]] ;;
    "image inspect") fake_image_inspect "$@" ;;
    "container inspect") fake_container_inspect "$@" ;;
    "container exec")
      generation="$(cat "$FAKE_GENERATION")"
      [[ "$#" -eq 9 && "$3" == "fake-container-$generation"
          && "$4" == pg_isready && "$5" == --quiet
          && "$6" == --username && "$7" == mahoraga_demo
          && "$8" == --dbname && "$9" == mahoraga_demo
          && "$FAKE_READY" == 1 ]]
      ;;
    "container stop")
      generation="$(cat "$FAKE_GENERATION")"
      [[ "$#" -eq 5 && "$3" == --time && "$4" == 10
          && "$5" == "fake-container-$generation" ]] || return 4
      printf 'docker stop %s\n' "$generation" >> "$FAKE_EVENTS"
      [[ "${FAKE_STOP_FAIL:-0}" != 1 ]] || return 9
      printf exited > "$FAKE_STATE"
      ;;
    "container rm")
      generation="$(cat "$FAKE_GENERATION")"
      [[ "$#" -eq 3 && "$3" == "fake-container-$generation" ]] || return 4
      [[ "${FAKE_REMOVE_FAIL:-0}" != 1 ]] || return 9
      printf 'docker rm %s\n' "$generation" >> "$FAKE_EVENTS"
      printf none > "$FAKE_STATE"
      ;;
    "run --detach") fake_run "$@" ;;
    *) return 5 ;;
  esac
}

fake_lsof() {
  [[ "${FAKE_PORT_BUSY:-0}" == 1 ]]
}

fake_shasum() {
  local file="${!#}"
  if [[ "$(cat "$file")" == *changed* ]]; then
    printf '%s  %s\n' \
      cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc "$file"
  else
    printf '%s  %s\n' \
      aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$file"
  fi
}

case "$TOOL" in
  java) fake_java "$@" ;;
  docker) fake_docker "$@" ;;
  lsof) fake_lsof ;;
  shasum) fake_shasum "$@" ;;
  sleep) exit 0 ;;
  *) exit 127 ;;
esac
