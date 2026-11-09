#!/bin/sh
# Run on the VPS: rebuild and restart the SpeakEasy container from /opt/speakeasy.
set -e
cd /opt/speakeasy
git pull --ff-only
docker build -t speakeasy server
docker rm -f speakeasy 2>/dev/null || true
docker run -d --name speakeasy --restart unless-stopped \
  --network nginx-proxy-manager_default \
  --env-file /opt/speakeasy/.env \
  -v /opt/speakeasy/data:/data \
  --memory 200m \
  speakeasy
sleep 3
docker exec speakeasy python -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8000/health').read().decode())"
