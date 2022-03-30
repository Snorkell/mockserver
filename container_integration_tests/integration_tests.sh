#!/usr/bin/env bash
# shellcheck disable=SC2155

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/logging.sh"
source "${SCRIPT_DIR}/helm-deploy.sh"
source "${SCRIPT_DIR}/docker-compose.sh"

# SKIP_JAVA_BUILD=true ./integration_tests.sh
# SKIP_HELM_TESTS=true SKIP_JAVA_BUILD=true ./container_integration_tests/integration_tests.sh

function build_docker() {
  runCommand "cd ${SCRIPT_DIR}"
  if [[ "${SKIP_JAVA_BUILD:-}" != "true" ]]; then
    runCommand "cd ..; ./mvnw -DskipTests=true package; cd -"
  fi
  runCommand "cp ../mockserver-netty/target/mockserver-netty-*-SNAPSHOT-shaded.jar ../docker/mockserver-netty-shaded.jar"
  runCommand "docker build --no-cache -t mockserver/mockserver:integration_testing --build-arg source=copy ../docker"
  runCommand "rm ../docker/mockserver-netty-shaded.jar"
  runCommand "docker build -t mockserver/mockserver:integration_testing_client -f ClientDockerfile ."
}

function test() {
  export TEST_CASE="${1}"
  printMessage "Running Test: \"${TEST_CASE}\""
  runCommand "cd ${SCRIPT_DIR}/${TEST_CASE}"
  runCommand "./integration_test.sh" || return 1
  runCommand "cd ${SCRIPT_DIR}"
}

function run_all_tests() {
  if [[ "${SKIP_HELM_TESTS:-}" != "true" ]]; then
    start-up-k8s &
  fi
  export PASS_LOG_FILE=$(mktemp)
  export FAIL_LOG_FILE=$(mktemp)

  if [[ "${SKIP_ALL_TESTS:-}" != "true" ]]; then
    set +euo pipefail
    if [[ "${SKIP_DOCKER_TESTS:-}" != "true" ]]; then
      # docker compose test
      test "docker_compose_forward_with_override"
      test "docker_compose_remote_host_and_port_by_environment_variable"
      test "docker_compose_server_port_by_command"
      test "docker_compose_server_port_by_environment_variable_long_name"
      test "docker_compose_server_port_by_environment_variable_short_name"
      test "docker_compose_without_server_port"
      test "docker_compose_with_expectation_initialiser"
      test "docker_compose_with_persisted_expectations"
      test "docker_compose_with_server_port_from_default_properties_file"
      test "docker_compose_with_server_port_from_custom_properties_file"
      clean-up-docker-containers
    fi
    if [[ "${SKIP_HELM_TESTS:-}" != "true" ]]; then
      # helm test
      start-up-k8s
      test "helm_default_config"
      test "helm_local_docker_container"
      test "helm_custom_server_port"
      test "helm_remote_host_and_port"
      tear-down-k8s
    fi
    set -euo pipefail
  fi

  printMessage "TEST SUMMARY"
  if [[ -s "${PASS_LOG_FILE}" ]]; then
    NUMBER_OF_PASSED_TESTS=$(cat "${PASS_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "PASSED: ${NUMBER_OF_PASSED_TESTS}"
    cat "${PASS_LOG_FILE}"
    rm "${PASS_LOG_FILE}"
    printf "\n\n"
  fi
  if [[ -s "${FAIL_LOG_FILE}" ]]; then
    NUMBER_OF_FAILED_TESTS=$(cat "${FAIL_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "FAILED: ${NUMBER_OF_FAILED_TESTS}"
    cat "${FAIL_LOG_FILE}"
    rm "${FAIL_LOG_FILE}"
    printf "\n\n"
    EXIT_CODE=1
  fi

  exit ${EXIT_CODE:-0}
}

build_docker
run_all_tests
