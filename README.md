# deployment-worker

Kafka worker that deploys generated images as Kubernetes Deployments and ClusterIP Services, waits for rollout, reports lifecycle outcomes, and removes owned resources on undeploy or permanent cleanup.

Platform guides: [ScaffoldOps documentation](https://github.com/ScaffoldOps/scaffoldops-docs) · [local setup](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/local-development.md) · [configuration](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/configuration.md) · [known gaps](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/findings.md).

## Local requirements

Java 17, Maven 3.9 for the container build, a reachable Kafka broker and authenticated generator-api callbacks (for workers). Run the worker inside Kubernetes with its ServiceAccount and provision target namespace RBAC. The native client uses the mounted ServiceAccount token and cluster CA via `ClientBuilder.cluster()`; it does not read KUBECONFIG. Host execution requires an explicit client configuration change; the supported local workflow runs the worker in Minikube.

Kafka port-forward alone does not fix broker metadata advertising `kafka:9092`; use a broker with a host-reachable advertised listener for host execution. The cluster path is the documented MVP setup.

## Configuration

| Variable | Use |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Default `localhost:9092` |
| `GENERATOR_API_BASE_URL` | Default `http://generator-api:8081/api/generator/v1` |
| `GENERATOR_API_TOKEN_URL`, `GENERATOR_API_CLIENT_ID`, `GENERATOR_API_CLIENT_SECRET` | OAuth callback credentials; client ID default `deployment-worker` |
| `GENERATOR_API_TOKEN` | Local static-token alternative |
| `GENERATED_CONTAINER_PORT` | Default `8080` |
| `DEPLOYMENT_ROLLOUT_TIMEOUT`, `DEPLOYMENT_POLL_INTERVAL` | Default `120s`, `1s`; apply to rollout and deletion waits |
| `DEPLOYMENT_REQUESTED_TOPIC`, `UNDEPLOYMENT_REQUESTED_TOPIC`, `ARTIFACT_CLEANUP_REQUESTED_TOPIC` | Topic overrides |

Kubernetes requires `deployment-worker-oauth` with `token-url` and `client-secret`, plus `docker-hub-pull-secret` of type `kubernetes.io/dockerconfigjson` in the worker namespace. The worker has no PostgreSQL dependency. Its Kafka group is `deployment-worker`.

## Build, test and run

Run from this repository root after provisioning the dependencies and exporting the variables above:

```bash
mvn clean test
mvn clean package -DskipTests
```

## Docker and Kubernetes

```bash
docker build -f Dockerfile -t victodomvar/scaffoldops-deployment-worker:local .
```

The Dockerfile builds the JAR and runs Java only. The runtime contains no kubectl binary. `io.kubernetes:client-java:23.0.0` matches Kubernetes 1.32 ([official compatibility matrix](https://github.com/kubernetes-client/java/wiki/2.-Versioning-and-Compatibility)). CI publishes `victodomvar/scaffoldops-deployment-worker` with `latest` and full commit SHA; both branch pipelines deploy `latest`. GitHub Actions requires repository/organization secrets `DOCKER_USERNAME` and `DOCKER_PASSWORD` (a Docker Hub access token).

For local Minikube (Kubernetes 1.32), first provision Kafka, generator-api, the `scaffoldops-dev` namespace and the secrets described above. Then build and load the local image:

```bash
minikube start --kubernetes-version=v1.32.0 # for a new local cluster
docker build -t scaffoldops/deployment-worker:local .
minikube image load scaffoldops/deployment-worker:local
kubectl kustomize k8s/deployment # render and inspect
kubectl apply --dry-run=server -k k8s/deployment
kubectl apply -k k8s/deployment
kubectl -n scaffoldops-dev set image deploy/deployment-worker deployment-worker=scaffoldops/deployment-worker:local
kubectl -n scaffoldops-dev rollout status deploy/deployment-worker
```

The supplied Role/RoleBinding grants get/list/watch/create/patch/delete on Deployments and Services in `generated-dev`. Readiness polls Deployment status (observed generation, updated, ready and available replicas); it needs no ReplicaSet, pod or event permissions. Undeploy lists by both ScaffoldOps ownership labels, deletes with UID/resourceVersion preconditions and waits for removal. Extra target namespaces need equivalent bindings. Generated pods do not receive imagePullSecrets automatically.

Do not mount a kubeconfig or kubectl wrapper into the worker. If an existing deployment still has a temporary workaround, remove its KUBECONFIG environment variable and kubeconfig/wrapper volume mounts and volumes before rollout. The repository manifests contain none. ServiceAccount token automount must remain enabled (the Kubernetes default).

Check authorization from your workstation:

```bash
kubectl auth can-i create deployments -n generated-dev --as=system:serviceaccount:scaffoldops-dev:deployment-worker
kubectl auth can-i patch services -n generated-dev --as=system:serviceaccount:scaffoldops-dev:deployment-worker
```

Through generator-api, the public contract stays `POST /generation-requests/{id}/deployment` with `{"namespace":"generated-dev","replicas":1}`, and `DELETE /generation-requests/{id}/deployment`. After a deploy, inspect the generated Deployment/Service in `generated-dev`; after undeploy, both should be absent. A rollout timeout reports `DEPLOYMENT_FAILED`. kubectl in these instructions and CI is an operator tool only, not a worker runtime dependency.

## GitHub Actions

- `pr-checks.yml`: Maven verification and tests for PRs to `develop`/`main` and feature branch pushes.
- `develop-pipeline.yml`: verify, test, build/push image, deploy to `scaffoldops-dev`.
- `main-pipeline.yml`: corresponding PRE pipeline targeting `scaffoldops-pre`.
- `deploy-k8s.yml`: reusable `workflow_call` deployment, selects context `minikube` and waits for rollout; it is not manually dispatchable.

Jobs use self-hosted runners. PRE needs additional infrastructure; see [delivery guide](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/delivery.md).

## Troubleshooting

Check `kubectl config current-context`, pods, events and component logs before restarting. For `ImagePullBackOff`, check the image/tag and namespace-local registry secret. `docker-hub-credentials` is Opaque application configuration and must never be used as `imagePullSecrets`. WSL runners need Linux Docker, daemon access, and a readable kubeconfig; a WindowsApps Docker shim can cause EACCES. Port conflicts require changing the local side of the port-forward. See [operations](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/operations.md) for commands and lifecycle diagnostics.
