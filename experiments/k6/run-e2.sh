#!/bin/bash

STRATEGY=$1

if [ -z "$STRATEGY" ]; then
  echo "Usage: ./experiments/k6/run-e2.sh <STRATEGY>"
  exit 1
fi

echo "===== E2 / Redis Failure / ${STRATEGY} ====="

k6 run \
  --summary-export="experiments/k6/results/e2/${STRATEGY}.json" \
  experiments/k6/e2-availability.js

echo "===== E2 / ${STRATEGY} 완료 ====="