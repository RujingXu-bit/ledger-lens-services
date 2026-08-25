#!/usr/bin/env bash
# Writes each service's generated OpenAPI description to docs/openapi/.
#
# The spec is generated from the code, so it cannot drift from what the service
# does - but it can drift from what consumers were promised. Committing it means
# an API change arrives as a reviewable diff instead of as a surprise for
# whoever is calling.
#
# Requires both services running:
#   ./mvnw -pl transaction-service spring-boot:run
#   ./mvnw -pl analytics-service  spring-boot:run
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p docs/openapi

for spec in "transaction-service:8081" "analytics-service:8082"; do
    name="${spec%%:*}"
    port="${spec##*:}"
    if ! curl -sf "http://localhost:${port}/v3/api-docs.yaml" -o "docs/openapi/${name}.yaml"; then
        echo "could not reach ${name} on port ${port} - is it running?" >&2
        exit 1
    fi
    echo "wrote docs/openapi/${name}.yaml ($(wc -l < "docs/openapi/${name}.yaml" | tr -d ' ') lines)"
done
