# Infra — dhl-demo

This repo owns ALL shared infrastructure. The frontend repo only deploys its
own image into the cluster created here. The "parcel machine" is always the
simulator — there is no real-machine mode.

## Local dev

```bash
docker compose -f infra/docker-compose.yml up -d   # postgres :12432, keycloak :12081, redpanda :12092
./gradlew bootRun                                  # BFF + locker-sim in one process on :12080
```

Or fully containerized (no JVM on the host needed):

```bash
docker compose -f infra/docker-compose.yml --profile full up -d --build
curl -s localhost:12080/actuator/health
```

Demo login (realm `courier`): user `koerier`, password in `infra/.env` (`DEMO_USER_PASSWORD`).

Watch the delivery events flow:

```bash
docker compose -f infra/docker-compose.yml exec redpanda rpk topic consume delivery-events
```

## Server provisioning (one time)

```bash
# 0. DNS first: A-records for dhl-{courier,locker,api,auth}.vanharen-it.nl → 167.233.127.230
ssh root@167.233.127.230 bash -s < infra/bootstrap/server-bootstrap.sh

# kubeconfig to the workstation
scp root@167.233.127.230:/etc/rancher/k3s/k3s.yaml ~/.kube/dhl-demo.yaml
sed -i '' 's/127.0.0.1/167.233.127.230/' ~/.kube/dhl-demo.yaml
export KUBECONFIG=~/.kube/dhl-demo.yaml

# cert-manager + issuer
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
kubectl -n cert-manager wait --for=condition=Available deploy --all --timeout=180s

# namespace, secrets (from infra/.env — fill in GHCR_USERNAME/GHCR_PAT first), everything else
kubectl apply -f infra/k8s/namespace.yaml
./infra/apply-secrets.sh
kubectl apply -k infra/k8s/
```

Secrets live in `infra/.env` (gitignored; see `.env.example`). The locker
client secret must match `keycloak/realm-locker.json` — it is a demo value,
the realm import sets it.

## Sanity checks

```bash
kubectl -n dhl-demo get pods   # postgres, redpanda, keycloak, dhl-backend, locker-sim Running
curl -s https://dhl-auth.vanharen-it.nl/realms/courier/.well-known/openid-configuration | head -c 200
curl -s https://dhl-api.vanharen-it.nl/actuator/health
# locker-sim must NOT be publicly reachable (ClusterIP only — the BFF is the only road in):
curl -s https://dhl-api.vanharen-it.nl/locker-api/sim/state   # 404 from ingress
```

## Demo reset between runs

```bash
kubectl -n dhl-demo delete job demo-reset --ignore-not-found
kubectl apply -f infra/k8s/seed-job.yaml
# plus: POST /api/sim/reset from the machine page (resets the sim's memory)
```

## Deploy a new backend build

CI pushes `ghcr.io/niels-van-haren-holding-b-v/dhl-backend:latest` on main; then:

```bash
kubectl -n dhl-demo rollout restart deploy dhl-backend locker-sim
```
