#!/bin/bash

REPO="millionco/react-doctor"
DATES=("2026-05-30" "2026-05-31" "2026-06-01" "2026-06-02" "2026-06-03" "2026-06-04" "2026-06-05")

echo "레포: $REPO | 기간: 2026-05-30 ~ 2026-06-05"
echo "================================================"

for DATE in "${DATES[@]}"; do
    echo "[ $DATE ]"
    for i in {0..23}; do
        FILE="${DATE}-${i}.json.gz"
        URL="https://data.gharchive.org/${FILE}"

        curl -s -O "$URL"

        COUNT=$(gzcat "$FILE" 2>/dev/null | grep "\"$REPO\"" | grep '"PullRequestEvent"' | wc -l)

        if [ "$COUNT" -gt 0 ]; then
            gzcat "$FILE" | grep "\"$REPO\"" | grep '"PullRequestEvent"' | python3 -c "
import sys, json
for line in sys.stdin:
    e = json.loads(line)
    payload = e.get('payload', {})
    action = payload.get('action', '')
    pr = payload.get('pull_request', {})
    merged = pr.get('merged', False)
    pr_num = payload.get('number', '')
    created_at = e.get('created_at', '')
    print(f'  {created_at} | action={action} | merged={merged} | PR#{pr_num}')
"
        fi

        rm -f "$FILE"
    done
done

echo "================================================"
echo "완료"
