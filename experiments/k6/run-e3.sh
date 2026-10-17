#!/bin/bash

STRATEGY=$1

if [ -z "$STRATEGY" ]; then
  echo "Usage: ./experiments/k6/run-e3.sh <STRATEGY>"
  exit 1
fi

echo "===== E3 / Redis-DB Inconsistency / ${STRATEGY} ====="

echo "===== DB Query Count Reset ====="
curl -s -X POST http://localhost:8080/api/experiment/metrics/reset

echo

k6 run \
  --summary-export="experiments/k6/results/e3/${STRATEGY}.json" \
  experiments/k6/e3-consistency.js

echo "===== DB Query Count ====="

curl -s http://localhost:8080/api/experiment/metrics \
  > "experiments/k6/results/e3/${STRATEGY}-metrics.json"

echo
echo "===== E3 / ${STRATEGY} 종료 ====="