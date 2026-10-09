# deployment-worker

Kafka worker that deploys generated images as Kubernetes Deployments and ClusterIP Services, waits for rollout, reports lifecycle outcomes, and removes owned resources on undeploy or permanent cleanup.

Platform guides: [ScaffoldOps documentation](https://github.com/ScaffoldOps/scaffoldops-docs) · [local setup](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/local-development.md) · [configuration](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/configuration.md) · [known gaps](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/findings.md).

## Local requirements

Java 17, Maven 3.9 for the container build, a reachable Kafka broker and authenticated generator-api callbacks (for workers). Install kubectl and select the intended kubeconfig/context; provision target namespace RBAC.

Kafka port-forward alone does not fix broker metadata advertising `kafka:9092`; use a broker with a host-reachable advertised listener for host execution. The cluster path is the documented MVP setup.

## Configuration

| Variable | Use |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Default `localhost:9092` |
| `GENERATOR_API_BASE_URL` | Set explicitly, including `/api/generator/v1` |
| `GENERATOR_API_TOKEN_URL`, `GENERATOR_API_CLIENT_ID`, `GENERATOR_API_CLIENT_SECRET` | OAuth callback credentials; client ID default `deployment-worker` |
| `GENERATOR_API_TOKEN` | Local static-token alternative |
| `GENERATED_CONTAINER_PORT` | Default `8080` |
| `DEPLOYMENT_REQUESTED_TOPIC`, `UNDEPLOYMENT_REQUESTED_TOPIC`, `ARTIFACT_CLEANUP_REQUESTED_TOPIC` | Topic overrides |

Kubernetes requires `deployment-worker-oauth` with `token-url` and `client-secret`, plus `docker-hub-pull-secret` of type `kubernetes.io/dockerconfigjson` in the worker namespace. The worker has no PostgreSQL dependency. Its Kafka group is `deployment-worker`.

## Build, test and run

Run from this repository root after provisioning the dependencies and exporting the variables above:

```bash
mvn clean test
mvn clean package -DskipTests
GENERATOR_API_BASE_URL=http://localhost:8081/api/generator/v1 GENERATOR_API_TOKEN="$TOKEN" mvn spring-boot:run
```

## Docker and Kubernetes

```bash
docker build -f Dockerfile -t victodomvar/scaffoldops-deployment-worker:local .
```

The Dockerfile builds the JAR and copies kubectl into the runtime image. CI publishes `victodomvar/scaffoldops-deployment-worker` with `latest` and full commit SHA; both branch pipelines deploy `latest`. GitHub Actions requires repository/organization secrets `DOCKER_USERNAME` and `DOCKER_PASSWORD` (a Docker Hub access token).

After applying shared infrastructure and creating component secrets:

```bash
kubectl apply -k k8s/deployment
kubectl -n scaffoldops-dev set image deploy/deployment-worker deployment-worker=victodomvar/scaffoldops-deployment-worker:latest
kubectl -n scaffoldops-dev set env deploy/deployment-worker GENERATOR_API_BASE_URL=http://generator-api-service/api/generator/v1
kubectl -n scaffoldops-dev rollout status deploy/deployment-worker
```

The supplied Role/RoleBinding grants access only to Deployments and Services in `generated-dev`. Extra target namespaces need equivalent bindings. Direct manifests use a different image from CI and an incomplete API URL; the commands above override them. Generated pods do not receive imagePullSecrets automatically.

## GitHub Actions

- `pr-checks.yml`: Maven verification and tests for PRs to `develop`/`main` and feature branch pushes.
- `develop-pipeline.yml`: verify, test, build/push image, deploy to `scaffoldops-dev`.
- `main-pipeline.yml`: corresponding PRE pipeline targeting `scaffoldops-pre`.
- `deploy-k8s.yml`: reusable `workflow_call` deployment, selects context `minikube` and waits for rollout; it is not manually dispatchable.

Jobs use self-hosted runners. PRE needs additional infrastructure; see [delivery guide](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/delivery.md).

## Troubleshooting

Check `kubectl config current-context`, pods, events and component logs before restarting. For `ImagePullBackOff`, check the image/tag and namespace-local registry secret. `docker-hub-credentials` is Opaque application configuration and must never be used as `imagePullSecrets`. WSL runners need Linux Docker, daemon access, and a readable kubeconfig; a WindowsApps Docker shim can cause EACCES. Port conflicts require changing the local side of the port-forward. See [operations](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/operations.md) for commands and lifecycle diagnostics.
