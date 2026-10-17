#!/bin/bash

VUS=$1

if [ -z "$VUS" ]; then
  echo "Usage: ./experiments/k6/run-e1-redis.sh <VUS>"
  exit 1
fi

echo "===== E1 / Redis Denylist / ${VUS} VU ====="

echo "===== 20초 Warm-up ====="
k6 run experiments/k6/warm-up.js

echo "===== Warm-up 완료 / 30초 대기 ====="
sleep 30

for i in {1..5}; do
  echo "===== Redis Denylist / ${VUS} VU / Run ${i} ====="

  VUS=$VUS k6 run \
    --summary-export="experiments/k6/results/e1/redis-${VUS}-run${i}.json" \
    experiments/k6/e1-performance.js

  if [ $i -lt 5 ]; then
      echo "===== 30초 대기 ====="
      sleep 30
  fi
done

echo "===== E1 / Redis Denylist / ${VUS} VU 완료 ====="
