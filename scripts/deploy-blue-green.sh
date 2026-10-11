#!/usr/bin/env bash
# 블루-그린 무중단 배포 (서버 1대)
#   1) 쉬고 있는 색(blue/green)을 새 버전으로 빌드·기동 — 그동안 기존 색이 계속 트래픽 처리
#   2) 새 색의 준비 상태(/actuator/health/readiness)가 UP일 때까지 대기 — 실패하면 새 색만 내리고 중단
#   3) Nginx upstream을 새 색으로 바꾸고 reload (진행 중인 연결은 끊지 않음)
#   4) 기존 색 정상 종료 — 진행 중인 요청·정시 작업을 마치고 내려감 (겹치는 동안 정시 작업 중복은 ShedLock이 방지)
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f ${COMPOSE_FILE:-compose.prod.yml}"
UPSTREAM_FILE="nginx/conf.d/00-backend-upstream.conf"
NGINX_CONTAINER="${NGINX_CONTAINER:-gitgalaxy-nginx}"
LEGACY_CONTAINER="gitgalaxy-backend-prod"   # 블루-그린 도입 전 단일 컨테이너
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-240}"

port_of() { [ "$1" = "blue" ] && echo "${BLUE_PORT:-8081}" || echo "${GREEN_PORT:-8082}"; }
log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 현재 활성 색: upstream 파일 기준 (없으면 첫 배포)
current=""
if [ -f "$UPSTREAM_FILE" ]; then
  current=$(grep -oE 'backend-(blue|green)' "$UPSTREAM_FILE" | head -1 | sed 's/backend-//' || true)
fi
if [ "$current" = "blue" ]; then next="green"; else next="blue"; fi
log "활성 색: ${current:-없음(첫 배포)} → 배포 대상: $next"

# 0) 인프라 서비스: 설정이 바뀐 경우에만 재생성 (backend는 아래에서 따로)
$COMPOSE up -d ${INFRA_SERVICES:-postgres redis elasticsearch prometheus grafana}

# 1) 새 색 빌드·기동
log "backend-$next 빌드"
$COMPOSE build "backend-$next"
log "backend-$next 기동"
$COMPOSE up -d --no-deps --force-recreate "backend-$next"

# 2) 준비 상태 확인
port=$(port_of "$next")
log "준비 상태 확인: http://127.0.0.1:$port/actuator/health/readiness (최대 ${HEALTH_TIMEOUT_SECONDS}초)"
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
until curl -fsS "http://127.0.0.1:$port/actuator/health/readiness" 2>/dev/null | grep -q '"UP"'; do
  if [ $SECONDS -ge $deadline ]; then
    log "backend-$next 준비 실패 → 새 색만 내리고 기존 색(${current:-없음}) 유지"
    $COMPOSE logs --tail 50 "backend-$next" || true
    $COMPOSE stop "backend-$next" || true
    exit 1
  fi
  sleep 3
done
log "backend-$next 준비 완료"

# 3) Nginx 전환 (설정 검사 실패 시 이전 upstream으로 되돌림)
backup=""
[ -f "$UPSTREAM_FILE" ] && backup=$(cat "$UPSTREAM_FILE")
printf 'upstream backend {\n    server backend-%s:8080;\n}\n' "$next" > "$UPSTREAM_FILE"
if docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER"; then
  if docker exec "$NGINX_CONTAINER" nginx -t; then
    docker exec "$NGINX_CONTAINER" nginx -s reload
  else
    log "Nginx 설정 검사 실패 → upstream 되돌리고 새 색 내림"
    if [ -n "$backup" ]; then printf '%s\n' "$backup" > "$UPSTREAM_FILE"; else rm -f "$UPSTREAM_FILE"; fi
    $COMPOSE stop "backend-$next" || true
    exit 1
  fi
else
  $COMPOSE up -d nginx
fi
log "트래픽 전환 완료 → backend-$next"

# 4) 기존 색 정상 종료 (진행 중인 정시 작업이 있으면 끝날 때까지 최대 11분)
if [ -n "$current" ]; then
  log "backend-$current 정상 종료 시작"
  $COMPOSE stop "backend-$current"
fi
if docker ps -a --format '{{.Names}}' | grep -qx "$LEGACY_CONTAINER"; then
  log "블루-그린 도입 전 컨테이너($LEGACY_CONTAINER) 정상 종료·삭제"
  docker stop -t 660 "$LEGACY_CONTAINER" || true
  docker rm "$LEGACY_CONTAINER" || true
fi
log "배포 완료: 활성 색 = $next"
