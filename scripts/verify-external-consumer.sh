#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
temporary_root=$(mktemp -d /tmp/droidproof-external-consumer.XXXXXX)
if [[ -z "${DROIDPROOF_KEEP_EXTERNAL_CONSUMER:-}" ]]; then
  trap 'rm -rf "$temporary_root"' EXIT
else
  echo "Keeping external-consumer workspace: $temporary_root"
fi
# The Maven repository and consumer build are fresh for every run. Reusing an
# explicitly supplied Gradle home avoids downloading a second Gradle
# distribution, while the consumer repository list and --offline mode ensure
# that no Maven Local entry or network repository participates in resolution.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/droidproof-gradle}"

maven_repository="$temporary_root/maven repository"
cli_working_directory="$temporary_root/CLI working directory"
consumer_directory="$temporary_root/Gradle consumer"
sample_bundle="$repository_root/droidproof-evidence/build/droidproof-samples/proof-checkout-offline-retry"
launcher="$repository_root/droidproof-cli/build/install/droidproof/bin/droidproof"

if [[ -n "${DROIDPROOF_SKIP_BUILD:-}" ]]; then
  maven_repository=${DROIDPROOF_MAVEN_REPOSITORY:?DROIDPROOF_MAVEN_REPOSITORY is required when skipping the build}
else
  "$repository_root/gradlew" --no-daemon --no-configuration-cache --console=plain \
    -Pdroidproof.publishRepository="$maven_repository" publish
  "$repository_root/gradlew" --no-daemon --no-configuration-cache --console=plain \
    -Pdroidproof.publishRepository="$maven_repository" publishRuntimeDependenciesToIsolatedRepository
  "$repository_root/gradlew" --no-daemon --no-configuration-cache --console=plain \
    :droidproof-cli:installDist :droidproof-evidence:generateSampleEvidence
fi

mkdir -p "$cli_working_directory/scenarios with spaces" "$cli_working_directory/bundles with spaces"
cp "$repository_root/samples/smoke-app/scenarios/compose-semantics-passing.json" \
  "$cli_working_directory/scenarios with spaces/valid scenario.json"
cp -R "$sample_bundle" "$cli_working_directory/bundles with spaces/sample bundle"
printf '%s\n' '{"schemaVersion":6}' > "$cli_working_directory/scenarios with spaces/invalid scenario.json"

"$launcher" --help >/dev/null
"$launcher" --version >/dev/null
"$launcher" validate-scenario --scenario "$cli_working_directory/scenarios with spaces/valid scenario.json" >/dev/null
"$launcher" report \
  --bundle "$cli_working_directory/bundles with spaces/sample bundle" \
  --output "$cli_working_directory/report with spaces.html" >/dev/null

expect_exit() {
  local expected=$1
  shift
  set +e
  "$@" >/dev/null 2>&1
  local actual=$?
  set -e
  if [[ "$actual" -ne "$expected" ]]; then
    echo "Expected exit $expected but got $actual: $*" >&2
    exit 1
  fi
}

expect_exit 2 "$launcher" validate-scenario --scenario "$cli_working_directory/scenarios with spaces/invalid scenario.json"
expect_exit 2 "$launcher" unknown-command
printf 'tampered\n' >> "$cli_working_directory/bundles with spaces/sample bundle/manifest.json"
expect_exit 1 "$launcher" report \
  --bundle "$cli_working_directory/bundles with spaces/sample bundle" \
  --output "$cli_working_directory/tampered report.html"

mkdir -p "$consumer_directory/scenarios"
"$repository_root/gradlew" --no-daemon --no-configuration-cache --console=plain \
  :droidproof-scenario-dsl:generateExternalConsumerScenario \
  -Pdroidproof.consumerScenarioPath="$consumer_directory/scenarios/consumer-proof.json"
cat > "$consumer_directory/settings.gradle" <<EOF
pluginManagement {
    repositories {
        maven {
            url = uri("$maven_repository")
            metadataSources { mavenPom(); artifact() }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("$maven_repository")
            metadataSources { mavenPom(); artifact() }
        }
    }
}
rootProject.name = "droidproof-external-consumer"
EOF
cat > "$consumer_directory/build.gradle" <<'EOF'
plugins {
    id 'io.github.fredleonam.droidproof' version '0.1.0-SNAPSHOT'
}

droidProof {
    scenario.set(layout.projectDirectory.file('scenarios/consumer-proof.json'))
    bundle.set(layout.projectDirectory.dir('sample evidence'))
    report.set(layout.buildDirectory.file('reports/consumer report.html'))
}
EOF
cp -R "$sample_bundle" "$consumer_directory/sample evidence"
(
  cd "$consumer_directory"
  "$repository_root/gradlew" --offline --no-daemon --no-configuration-cache --console=plain \
    droidProofValidateScenario droidProofReport
)

test -f "$consumer_directory/build/reports/consumer report.html"
