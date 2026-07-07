#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly ROOT
readonly JAR="$ROOT/target/mahoraga-memory-0.1.0-SNAPSHOT.jar"
readonly CONFIG="$ROOT/config/mahoraga.yml"
readonly DEMO_DIR="$ROOT/target/demo"
readonly CONTROL="$DEMO_DIR/control-evidence.json"
readonly MEMORY="$DEMO_DIR/memory-evidence.json"
readonly EVIDENCE="$DEMO_DIR/evidence.json"
readonly TRANSCRIPT="$DEMO_DIR/transcript.txt"
readonly CONTROL_STAGE="$DEMO_DIR/.control-evidence.$$.json"
readonly MEMORY_STAGE="$DEMO_DIR/.memory-evidence.$$.json"
readonly EVIDENCE_STAGE="$DEMO_DIR/.evidence.$$.json"
readonly TRANSCRIPT_STAGE="$DEMO_DIR/.transcript.$$.txt"
readonly OPERATION_LOG="$DEMO_DIR/.operation.$$.log"
readonly RUN_LOCK="$DEMO_DIR/.run.lock"
readonly RUN_LOCK_OWNER="$RUN_LOCK/owner"

readonly CONTAINER_NAME="mahoraga-memory-demo"
readonly IMAGE="postgres:18.4-alpine"
readonly LABEL_KEY="dev.mahoraga.memory.synthetic"
readonly DATABASE="mahoraga_demo"
readonly DATABASE_USER="mahoraga_demo"
readonly HOST="127.0.0.1"
readonly HOST_PORT="55432"
readonly CONTAINER_PORT="5432"
readonly NETWORK="bridge"
readonly TMPFS_PATH="/var/lib/postgresql"
readonly TMPFS_OPTIONS="rw,nosuid,nodev,noexec,size=536870912"

IMAGE_ID=""
IMAGE_ENTRYPOINT=""
IMAGE_COMMAND=""
BUILD_FINGERPRINT=""
DATABASE_PASSWORD=""
ACTIVE_CONTAINER_ID=""
CHILD_PID=""
PRESENT_MODE=0
RUN_STARTED=0
RUN_SUCCEEDED=0
LOCK_HELD=0

fail() {
  printf 'Demo failed: %s\n' "$1" >&2
  return 1
}

manual_recovery() {
  printf 'Manual recovery required for container "%s"; no unsafe cleanup was attempted.\n' \
    "$CONTAINER_NAME" >&2
  return 1
}

usage() {
  printf 'Usage: scripts/demo.sh preflight|rehearse|present\n' >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

require_file() {
  [[ -f "$1" && ! -L "$1" ]] || fail "$2 is missing or unsafe"
}

require_directory() {
  local real
  [[ -d "$1" && ! -L "$1" ]] || fail "$2 is missing or unsafe"
  real="$(cd "$1" && pwd -P)"
  [[ "$real" == "$1" ]] || fail "$2 is not the expected real directory"
}

sha256_file() {
  local line digest
  if command -v shasum >/dev/null 2>&1; then
    line="$(shasum -a 256 "$1")" || return 1
  elif command -v sha256sum >/dev/null 2>&1; then
    line="$(sha256sum "$1")" || return 1
  else
    fail "a SHA-256 command is unavailable"
    return 1
  fi
  digest="${line%% *}"
  [[ "${#digest}" -eq 64 && "$digest" != *[!0-9a-f]* ]] \
    || fail "the packaged artifact fingerprint is invalid"
  printf '%s\n' "$digest"
}

load_image_contract() {
  IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE" 2>/dev/null)" \
    || fail "the pinned PostgreSQL image is not available locally"
  IMAGE_ENTRYPOINT="$(
    docker image inspect --format '{{json .Config.Entrypoint}}' "$IMAGE" 2>/dev/null
  )" || fail "the pinned image entrypoint cannot be inspected"
  IMAGE_COMMAND="$(
    docker image inspect --format '{{json .Config.Cmd}}' "$IMAGE" 2>/dev/null
  )" || fail "the pinned image command cannot be inspected"
  [[ -n "$IMAGE_ID" ]] || fail "the pinned image identity is empty"
}

require_image_unchanged() {
  local image_id entrypoint command
  image_id="$(docker image inspect --format '{{.Id}}' "$IMAGE" 2>/dev/null)" \
    || fail "the pinned image is no longer available"
  entrypoint="$(
    docker image inspect --format '{{json .Config.Entrypoint}}' "$IMAGE" 2>/dev/null
  )" || fail "the pinned image entrypoint can no longer be inspected"
  command="$(
    docker image inspect --format '{{json .Config.Cmd}}' "$IMAGE" 2>/dev/null
  )" || fail "the pinned image command can no longer be inspected"
  [[ "$image_id" == "$IMAGE_ID"
      && "$entrypoint" == "$IMAGE_ENTRYPOINT"
      && "$command" == "$IMAGE_COMMAND" ]] \
    || fail "the pinned image changed after preflight"
}

require_build_unchanged() {
  [[ "$(sha256_file "$JAR")" == "$BUILD_FINGERPRINT" ]] \
    || fail "the packaged application changed during the demo"
}

container_id() {
  local id
  if id="$(
    docker container inspect --format '{{.Id}}' "$CONTAINER_NAME" 2>/dev/null
  )"; then
    printf '%s\n' "$id"
    return 0
  fi
  docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 || return 2
  return 1
}

inspect_container() {
  docker container inspect --format "$2" "$1" 2>/dev/null
}

guard_failure() {
  printf 'Unsafe demo container: %s does not match.\n' "$1" >&2
  manual_recovery
}

expect_field() {
  local actual
  actual="$(inspect_container "$1" "$3")" || {
    guard_failure "$2"
    return 1
  }
  [[ "$actual" == "$4" ]] || guard_failure "$2"
}

validate_environment() {
  local environment line db_count=0 user_count=0 password_count=0 invalid=0
  environment="$(
    inspect_container "$1" '{{range .Config.Env}}{{println .}}{{end}}'
  )" || {
    guard_failure "database environment"
    return 1
  }
  while IFS= read -r line; do
    case "$line" in
      "POSTGRES_DB=$DATABASE") db_count=$((db_count + 1)) ;;
      "POSTGRES_USER=$DATABASE_USER") user_count=$((user_count + 1)) ;;
      POSTGRES_PASSWORD=?*) password_count=$((password_count + 1)) ;;
      POSTGRES_*) invalid=1 ;;
    esac
  done <<< "$environment"
  [[ "$db_count" -eq 1
      && "$user_count" -eq 1
      && "$password_count" -eq 1
      && "$invalid" -eq 0 ]] \
    || guard_failure "database environment"
}

validate_runtime_mounts() {
  local mounts
  mounts="$(
    inspect_container "$1" \
      '{{range .Mounts}}{{printf "%s|%s|%s|%t\n" .Type .Source .Destination .RW}}{{end}}'
  )" || {
    guard_failure "runtime mounts"
    return 1
  }
  case "$mounts" in
    ""|"tmpfs||$TMPFS_PATH|true") ;;
    *) guard_failure "runtime mounts" ;;
  esac
}

guard_container() {
  local id="$1"
  expect_field "$id" "name" '{{.Name}}' "/$CONTAINER_NAME" || return
  expect_field "$id" "synthetic label" \
    "{{index .Config.Labels \"$LABEL_KEY\"}}" "true" || return
  expect_field "$id" "image reference" '{{.Config.Image}}' "$IMAGE" || return
  expect_field "$id" "image identity" '{{.Image}}' "$IMAGE_ID" || return
  expect_field "$id" "published-port count" \
    '{{len .HostConfig.PortBindings}}' "1" || return
  expect_field "$id" "loopback port binding" \
    "{{json (index .HostConfig.PortBindings \"$CONTAINER_PORT/tcp\")}}" \
    "[{\"HostIp\":\"$HOST\",\"HostPort\":\"$HOST_PORT\"}]" || return
  expect_field "$id" "bind mounts" '{{len .HostConfig.Binds}}' "0" || return
  expect_field "$id" "mount specifications" \
    '{{len .HostConfig.Mounts}}' "0" || return
  expect_field "$id" "inherited volumes" \
    '{{len .HostConfig.VolumesFrom}}' "0" || return
  expect_field "$id" "tmpfs count" '{{len .HostConfig.Tmpfs}}' "1" || return
  expect_field "$id" "tmpfs options" \
    "{{index .HostConfig.Tmpfs \"$TMPFS_PATH\"}}" "$TMPFS_OPTIONS" || return
  expect_field "$id" "restart policy" \
    '{{.HostConfig.RestartPolicy.Name}}' "no" || return
  expect_field "$id" "restart count" \
    '{{.HostConfig.RestartPolicy.MaximumRetryCount}}' "0" || return
  expect_field "$id" "automatic removal" \
    '{{.HostConfig.AutoRemove}}' "false" || return
  expect_field "$id" "network mode" \
    '{{.HostConfig.NetworkMode}}' "$NETWORK" || return
  expect_field "$id" "network count" \
    '{{len .NetworkSettings.Networks}}' "1" || return
  expect_field "$id" "attached network" \
    "{{with index .NetworkSettings.Networks \"$NETWORK\"}}true{{else}}false{{end}}" \
    "true" || return
  expect_field "$id" "entrypoint" \
    '{{json .Config.Entrypoint}}' "$IMAGE_ENTRYPOINT" || return
  expect_field "$id" "command" \
    '{{json .Config.Cmd}}' "$IMAGE_COMMAND" || return
  validate_environment "$id" || return
  validate_runtime_mounts "$id"
}

container_state() {
  inspect_container "$1" '{{.State.Status}}'
}

require_safe_state() {
  case "$(container_state "$1")" in
    running|created|exited) ;;
    *) guard_failure "container state" ;;
  esac
}

require_running() {
  [[ "$(container_state "$1")" == "running" ]] \
    || guard_failure "running state"
}

require_port_free() {
  local status
  if lsof -nP -iTCP:"$HOST_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    status=0
  else
    status=$?
  fi
  case "$status" in
    0) fail "the guarded local demo port is occupied" ;;
    1) return 0 ;;
    *) fail "the guarded local demo port cannot be inspected" ;;
  esac
}

run_lock_state() {
  local owner
  if [[ ! -e "$RUN_LOCK" && ! -L "$RUN_LOCK" ]]; then
    printf 'absent\n'
    return
  fi
  if [[ ! -d "$RUN_LOCK"
      || -L "$RUN_LOCK"
      || ! -f "$RUN_LOCK_OWNER"
      || -L "$RUN_LOCK_OWNER" ]]; then
    printf 'unsafe\n'
    return
  fi
  owner="$(cat "$RUN_LOCK_OWNER")" || {
    printf 'unsafe\n'
    return
  }
  case "$owner" in
    ""|*[!0-9]*) printf 'unsafe\n' ;;
    *)
      if kill -0 "$owner" 2>/dev/null; then
        printf 'live\n'
      else
        printf 'stale\n'
      fi
      ;;
  esac
}

check_run_lock() {
  case "$(run_lock_state)" in
    absent) ;;
    stale) printf 'Preflight: a stale demo run lock is safely recoverable.\n' ;;
    live) fail "another demo run is active" ;;
    *) fail "the existing demo run lock is unsafe" ;;
  esac
}

preflight() {
  local version id status state
  for command in java docker lsof od sed tr; do
    require_command "$command"
  done
  version="$(
    java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p'
  )" || fail "Java cannot be executed"
  case "$version" in
    21|21.*) ;;
    *) fail "Java 21 is required" ;;
  esac
  require_file "$ROOT/pom.xml" "pom.xml"
  [[ -x "$ROOT/mvnw" && -f "$ROOT/mvnw" && ! -L "$ROOT/mvnw" ]] \
    || fail "the Maven Wrapper is missing or unsafe"
  require_file "$CONFIG" "demo configuration"
  require_file "$JAR" "packaged application"
  require_directory "$ROOT/target" "target directory"
  if [[ -e "$DEMO_DIR" || -L "$DEMO_DIR" ]]; then
    require_directory "$DEMO_DIR" "demo output directory"
  fi
  check_run_lock
  java -jar "$JAR" --help >/dev/null 2>&1 \
    || fail "the packaged application cannot display help"
  docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 \
    || fail "the Docker daemon is unavailable"
  load_image_contract
  BUILD_FINGERPRINT="$(sha256_file "$JAR")"
  if id="$(container_id)"; then
    guard_container "$id"
    require_safe_state "$id"
    state="$(container_state "$id")"
    if [[ "$state" != "running" ]]; then
      require_port_free
    fi
    printf 'Preflight: an exact guarded orphan is recoverable.\n'
  else
    status=$?
    [[ "$status" -eq 1 ]] || fail "existing container state cannot be inspected"
    require_port_free
  fi
  printf 'Preflight passed.\n'
}

prepare_demo_directory() {
  if [[ -e "$DEMO_DIR" || -L "$DEMO_DIR" ]]; then
    require_directory "$DEMO_DIR" "demo output directory"
  else
    mkdir "$DEMO_DIR"
    require_directory "$DEMO_DIR" "demo output directory"
  fi
}

acquire_run_lock() {
  if mkdir "$RUN_LOCK" 2>/dev/null; then
    LOCK_HELD=1
    (
      set -o noclobber
      printf '%s\n' "$$" > "$RUN_LOCK_OWNER"
    ) 2>/dev/null || {
      fail "the demo run-lock owner could not be recorded"
      return 1
    }
    return
  fi
  case "$(run_lock_state)" in
    live) fail "another demo run is active" ;;
    stale) fail "the stale demo run lock was not recovered" ;;
    *) fail "the existing demo run lock is unsafe" ;;
  esac
}

recover_stale_run_lock() {
  local owner
  case "$(run_lock_state)" in
    absent) return ;;
    stale) ;;
    live) fail "another demo run is active"; return 1 ;;
    *) fail "the existing demo run lock is unsafe"; return 1 ;;
  esac
  owner="$(cat "$RUN_LOCK_OWNER")" || return 1
  kill -0 "$owner" 2>/dev/null && {
    fail "the stale demo run lock became active"
    return 1
  }
  [[ "$(cat "$RUN_LOCK_OWNER")" == "$owner" ]] \
    || {
      fail "the stale demo run-lock owner changed"
      return 1
    }
  rm "$RUN_LOCK_OWNER" || return 1
  rmdir "$RUN_LOCK" || return 1
}

release_run_lock() {
  local owner
  [[ "$LOCK_HELD" -eq 1 ]] || return 0
  [[ -d "$RUN_LOCK"
      && ! -L "$RUN_LOCK"
      && -f "$RUN_LOCK_OWNER"
      && ! -L "$RUN_LOCK_OWNER" ]] \
    || {
      fail "the owned demo run lock became unsafe"
      return 1
    }
  owner="$(cat "$RUN_LOCK_OWNER")" || return 1
  [[ "$owner" == "$$" ]] || {
    fail "the owned demo run-lock identity changed"
    return 1
  }
  rm "$RUN_LOCK_OWNER" || {
    fail "the owned demo run-lock owner could not be removed"
    return 1
  }
  rmdir "$RUN_LOCK" || {
    fail "the owned demo run lock could not be released"
    return 1
  }
  LOCK_HELD=0
}

remove_owned_files() {
  local path
  for path in "$@"; do
    if [[ -L "$path" || ( -e "$path" && ! -f "$path" ) ]]; then
      fail "an owned demo artifact path is unsafe"
      return 1
    fi
    rm -f "$path"
  done
}

clear_all_artifacts() {
  remove_owned_files \
    "$CONTROL" "$MEMORY" "$EVIDENCE" "$TRANSCRIPT" \
    "$CONTROL_STAGE" "$MEMORY_STAGE" "$EVIDENCE_STAGE" \
    "$TRANSCRIPT_STAGE" "$OPERATION_LOG"
}

clear_staging_artifacts() {
  remove_owned_files \
    "$CONTROL_STAGE" "$MEMORY_STAGE" "$EVIDENCE_STAGE" \
    "$TRANSCRIPT_STAGE" "$OPERATION_LOG"
}

reset_operation_log() {
  remove_owned_files "$OPERATION_LOG"
  (
    set -o noclobber
    : > "$OPERATION_LOG"
  ) 2>/dev/null || fail "the operation log path is unsafe"
}

phase() {
  printf '\n== %s ==\n' "$1"
  if [[ "$PRESENT_MODE" -eq 1 ]]; then
    sleep 1
  fi
}

generate_password() {
  DATABASE_PASSWORD="$(
    od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]'
  )"
  [[ "${#DATABASE_PASSWORD}" -eq 64 ]] \
    || fail "an ephemeral database password could not be generated"
}

wait_for_database() {
  local attempt=0
  while [[ "$attempt" -lt 30 ]]; do
    if docker container exec "$ACTIVE_CONTAINER_ID" \
      pg_isready --quiet --username "$DATABASE_USER" --dbname "$DATABASE" \
      >/dev/null 2>&1; then
      guard_container "$ACTIVE_CONTAINER_ID"
      require_running "$ACTIVE_CONTAINER_ID"
      return
    fi
    guard_container "$ACTIVE_CONTAINER_ID"
    require_running "$ACTIVE_CONTAINER_ID"
    attempt=$((attempt + 1))
    sleep 1
  done
  fail "the guarded demo database did not become ready"
}

start_database() {
  local id status
  require_image_unchanged
  require_build_unchanged
  if id="$(container_id)"; then
    fail "a demo container already exists"
    return 1
  else
    status=$?
    [[ "$status" -eq 1 ]] || fail "container absence cannot be established"
  fi
  require_port_free
  generate_password
  reset_operation_log
  if ! id="$(
    POSTGRES_PASSWORD="$DATABASE_PASSWORD" docker run \
      --detach \
      --pull=never \
      --name "$CONTAINER_NAME" \
      --label "$LABEL_KEY=true" \
      --restart=no \
      --network="$NETWORK" \
      --publish "$HOST:$HOST_PORT:$CONTAINER_PORT" \
      --tmpfs "$TMPFS_PATH:$TMPFS_OPTIONS" \
      --env "POSTGRES_DB=$DATABASE" \
      --env "POSTGRES_USER=$DATABASE_USER" \
      --env POSTGRES_PASSWORD \
      "$IMAGE" 2>"$OPERATION_LOG"
  )"; then
    fail "the guarded demo database could not be started"
    return 1
  fi
  [[ -n "$id" ]] || fail "Docker returned no container identity"
  ACTIVE_CONTAINER_ID="$id"
  guard_container "$ACTIVE_CONTAINER_ID"
  require_running "$ACTIVE_CONTAINER_ID"
  wait_for_database
}

cleanup_database() {
  local id status state remaining
  if id="$(container_id)"; then
    :
  else
    status=$?
    if [[ "$status" -eq 1 ]]; then
      ACTIVE_CONTAINER_ID=""
      DATABASE_PASSWORD=""
      return 0
    fi
    printf 'Cleanup failed because Docker state is unavailable.\n' >&2
    manual_recovery
    return 1
  fi
  if [[ -n "$ACTIVE_CONTAINER_ID" && "$id" != "$ACTIVE_CONTAINER_ID" ]]; then
    printf 'Cleanup refused because the container identity changed.\n' >&2
    manual_recovery
    return 1
  fi
  guard_container "$id" || return 1
  require_safe_state "$id" || return 1
  state="$(container_state "$id")"
  if [[ "$state" == "running" ]]; then
    docker container stop --time 10 "$id" >/dev/null 2>&1 || {
      printf 'Cleanup failed while stopping the exact guarded container.\n' >&2
      manual_recovery
      return 1
    }
    guard_container "$id" || return 1
    [[ "$(container_state "$id")" == "exited" ]] || {
      guard_failure "stopped state"
      return 1
    }
  fi
  docker container rm "$id" >/dev/null 2>&1 || {
    printf 'Cleanup failed while removing the exact guarded container.\n' >&2
    manual_recovery
    return 1
  }
  if remaining="$(container_id)"; then
    printf 'Cleanup refused because another container now owns the guarded name.\n' >&2
    manual_recovery
    return 1
  else
    status=$?
    [[ "$status" -eq 1 ]] || {
      manual_recovery
      return 1
    }
  fi
  ACTIVE_CONTAINER_ID=""
  DATABASE_PASSWORD=""
}

recover_safe_orphan() {
  local id status
  if id="$(container_id)"; then
    phase "Recovering exact guarded orphan"
    ACTIVE_CONTAINER_ID="$id"
    cleanup_database
  else
    status=$?
    [[ "$status" -eq 1 ]] || fail "orphan state cannot be inspected"
  fi
}

run_java() {
  local status
  reset_operation_log
  "$@" >"$OPERATION_LOG" 2>&1 &
  CHILD_PID=$!
  if wait "$CHILD_PID"; then
    status=0
  else
    status=$?
  fi
  CHILD_PID=""
  return "$status"
}

run_arm() {
  local mode="$1" output="$2"
  guard_container "$ACTIVE_CONTAINER_ID"
  require_running "$ACTIVE_CONTAINER_ID"
  require_build_unchanged
  export MAHORAGA_DB_URL="jdbc:postgresql://$HOST:$HOST_PORT/$DATABASE"
  export MAHORAGA_DB_USER="$DATABASE_USER"
  export MAHORAGA_DB_PASSWORD="$DATABASE_PASSWORD"
  if ! run_java java -jar "$JAR" demo "$CONFIG" arm \
      --synthetic-demo --mode "$mode" --output "$output"; then
    unset MAHORAGA_DB_URL MAHORAGA_DB_USER MAHORAGA_DB_PASSWORD
    fail "the $mode Java arm failed without publishing successful evidence"
    return 1
  fi
  unset MAHORAGA_DB_URL MAHORAGA_DB_USER MAHORAGA_DB_PASSWORD
  require_file "$output" "$mode arm evidence"
}

run_compare() {
  guard_container "$ACTIVE_CONTAINER_ID"
  require_running "$ACTIVE_CONTAINER_ID"
  require_build_unchanged
  unset MAHORAGA_DB_URL MAHORAGA_DB_USER MAHORAGA_DB_PASSWORD
  if ! run_java java -jar "$JAR" demo "$CONFIG" compare \
      --control "$CONTROL_STAGE" \
      --memory "$MEMORY_STAGE" \
      --output "$EVIDENCE_STAGE"; then
    fail "Java evidence comparison failed without publishing final evidence"
    return 1
  fi
  require_file "$EVIDENCE_STAGE" "final Java evidence"
}

json_scalar() {
  local value
  value="$(
    sed -n "s/.*\"$1\":\\([^,}]*\\).*/\\1/p" "$EVIDENCE_STAGE"
  )"
  [[ -n "$value" ]] || fail "validated evidence is missing render field $1"
  printf '%s' "$value"
}

json_string() {
  local value
  value="$(
    sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p" "$EVIDENCE_STAGE"
  )"
  [[ -n "$value" ]] || fail "validated evidence is missing render field $1"
  printf '%s' "$value"
}

json_array() {
  local value
  value="$(
    sed -n "s/.*\"$1\":\\(\\[[^]]*\\]\\).*/\\1/p" "$EVIDENCE_STAGE"
  )"
  [[ -n "$value" ]] || fail "validated evidence is missing render field $1"
  printf '%s' "$value" | tr -d '"' | sed 's/,/, /g'
}

validate_transcript_fields() {
  local field
  for field in candidate_ids control_executed_order memory_executed_order; do
    json_array "$field" >/dev/null || return
  done
  for field in outcome duplicate_retry_result conflicting_duplicate_result \
      missing_completion_result; do
    json_string "$field" >/dev/null || return
  done
  for field in control_actions_before_regression memory_actions_before_regression \
      has_zero_e2_events_at_planning pod_uid_name_ip_changed \
      canonical_deployment_unchanged posture_delta detected not_detected partial \
      new_count still_open_count verified_resolved_count regressed_count \
      not_retested_count inconclusive_count shuffle_digest_equality \
      transaction_failure_leaves_partial_state; do
    json_scalar "$field" >/dev/null || return
  done
}

render_transcript() {
  validate_transcript_fields
  {
    printf 'MAHORAGA MEMORY MVP\n'
    printf 'Synthetic local evidence\n\n'
    printf 'Steering\n'
    printf 'Candidate tests: %s\n' "$(json_array candidate_ids)"
    printf 'Memory disabled: %s\n' "$(json_array control_executed_order)"
    printf 'Memory enabled: %s\n' "$(json_array memory_executed_order)"
    printf 'Actions before regression detection: %s -> %s\n' \
      "$(json_scalar control_actions_before_regression)" \
      "$(json_scalar memory_actions_before_regression)"
    printf 'Zero E2 events at planning: %s\n\n' \
      "$(json_scalar has_zero_e2_events_at_planning)"
    printf 'Stable identity\n'
    printf 'Pod UID/name/IP changed: %s\n' \
      "$(json_scalar pod_uid_name_ip_changed)"
    printf 'Canonical Deployment unchanged: %s\n' \
      "$(json_scalar canonical_deployment_unchanged)"
    printf 'Weak-signal collision: %s\n' "$(json_string outcome)"
    printf 'Posture changes from ambiguous observation: %s\n\n' \
      "$(json_scalar posture_delta)"
    printf 'Stateless E2 view\n'
    printf 'Detected: %s\n' "$(json_scalar detected)"
    printf 'Not detected: %s\n' "$(json_scalar not_detected)"
    printf 'Partial: %s\n' "$(json_scalar partial)"
    printf 'Findings with no E2 fact: unrepresentable\n'
    printf 'Longitudinal classifications: unavailable\n\n'
    printf 'Memory-aware E1 + E2 view\n'
    printf 'NEW: %s\n' "$(json_scalar new_count)"
    printf 'STILL_OPEN: %s\n' "$(json_scalar still_open_count)"
    printf 'VERIFIED_RESOLVED: %s\n' "$(json_scalar verified_resolved_count)"
    printf 'REGRESSED: %s\n' "$(json_scalar regressed_count)"
    printf 'NOT_RETESTED: %s\n' "$(json_scalar not_retested_count)"
    printf 'INCONCLUSIVE: %s\n\n' "$(json_scalar inconclusive_count)"
    printf 'Correctness\n'
    printf 'Duplicate retry: %s\n' "$(json_string duplicate_retry_result)"
    printf 'Conflicting duplicate: %s\n' \
      "$(json_string conflicting_duplicate_result)"
    printf 'Missing completion sequence: %s\n' \
      "$(json_string missing_completion_result)"
    printf 'Shuffled ingestion report hash equal: %s\n' \
      "$(json_scalar shuffle_digest_equality)"
    printf 'Transaction failure leaves partial state: %s\n\n' \
      "$(json_scalar transaction_failure_leaves_partial_state)"
    printf 'Scope\n'
    printf 'Synthetic product-value demonstration; not a production privacy or security validation.\n'
  } > "$TRANSCRIPT_STAGE"
}

publish_artifacts() {
  local target
  for target in "$CONTROL" "$MEMORY" "$TRANSCRIPT" "$EVIDENCE"; do
    [[ ! -e "$target" && ! -L "$target" ]] \
      || fail "a final demo artifact path became unsafe"
  done
  mv "$CONTROL_STAGE" "$CONTROL"
  mv "$MEMORY_STAGE" "$MEMORY"
  mv "$TRANSCRIPT_STAGE" "$TRANSCRIPT"
  mv "$EVIDENCE_STAGE" "$EVIDENCE"
}

handle_signal() {
  local status="$1"
  trap - HUP INT TERM
  if [[ -n "$CHILD_PID" ]]; then
    kill -TERM "$CHILD_PID" 2>/dev/null || true
    wait "$CHILD_PID" 2>/dev/null || true
    CHILD_PID=""
  fi
  exit "$status"
}

handle_exit() {
  local status=$? cleanup_status=0
  trap - EXIT HUP INT TERM
  set +e
  if [[ "$RUN_STARTED" -eq 1 ]]; then
    cleanup_database
    cleanup_status=$?
  fi
  if [[ "$RUN_STARTED" -eq 1 ]]; then
    if [[ "$status" -ne 0
        || "$cleanup_status" -ne 0
        || "$RUN_SUCCEEDED" -ne 1 ]]; then
      clear_all_artifacts || cleanup_status=1
    else
      clear_staging_artifacts || cleanup_status=1
    fi
  fi
  if [[ "$LOCK_HELD" -eq 1 ]]; then
    if ! release_run_lock; then
      cleanup_status=1
      if [[ "$RUN_STARTED" -eq 1 ]]; then
        clear_all_artifacts || cleanup_status=1
      fi
    fi
  fi
  if [[ "$status" -eq 0 && "$cleanup_status" -ne 0 ]]; then
    status=1
  fi
  exit "$status"
}

install_traps() {
  trap 'handle_signal 129' HUP
  trap 'handle_signal 130' INT
  trap 'handle_signal 143' TERM
  trap handle_exit EXIT
}

run_workflow() {
  PRESENT_MODE="$1"
  phase "Preflight"
  preflight
  prepare_demo_directory
  install_traps
  recover_stale_run_lock
  acquire_run_lock
  recover_safe_orphan
  RUN_STARTED=1
  clear_all_artifacts

  phase "Memory disabled"
  start_database
  run_arm "memory-off" "$CONTROL_STAGE"
  cleanup_database

  phase "Memory enabled"
  start_database
  run_arm "memory-on" "$MEMORY_STAGE"

  phase "Comparing Java evidence"
  run_compare
  render_transcript
  cleanup_database
  require_build_unchanged
  publish_artifacts
  RUN_SUCCEEDED=1
  clear_staging_artifacts

  phase "Normalized stakeholder proof"
  cat "$TRANSCRIPT"
}

[[ "$#" -eq 1 ]] || usage

case "$1" in
  preflight) preflight ;;
  rehearse) run_workflow 0 ;;
  present) run_workflow 1 ;;
  *) usage ;;
esac
