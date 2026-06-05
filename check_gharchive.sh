#!/bin/bash

DATE=${1:-"2026-06-4"}
REPO=${2:-"millionco/react-doctor"}
EVENT=${3:-"PullRequestEvent"}

echo "날짜: $DATE | 레포: $REPO | 이벤트: $EVENT"
echo "================================================"

for i in {0..23}; do
    FILE="${DATE}-${i}.json.gz"
    URL="https://data.gharchive.org/${FILE}"

    echo -n "[${i}시] 다운로드 중..."
    curl -s -O "$URL"

    COUNT=$(gzcat "$FILE" 2>/dev/null | grep "\"$REPO\"" | grep "\"$EVENT\"" | wc -l)
    echo " $COUNT 개 발견"

    if [ "$COUNT" -gt 0 ]; then
        echo "--- 상세 ---"
        gzcat "$FILE" | grep "\"$REPO\"" | grep "\"$EVENT\"" | python3 -c "
import sys, json
for line in sys.stdin:
    e = json.loads(line)
    payload = e.get('payload', {})
    action = payload.get('action', '')
    pr = payload.get('pull_request', {})
    merged = pr.get('merged', False)
    print(f'  UTC {e[\"created_at\"]} | action={action} | merged={merged} | PR#{payload.get(\"number\",\"\")}')
"
        echo "------------"
    fi

    rm -f "$FILE"
done

echo "================================================"
echo "완료"
