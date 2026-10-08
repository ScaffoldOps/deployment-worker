# deployment-worker

MVP Spring Boot Kafka worker. Runs kubectl using its in-cluster service account (or your local kubeconfig). Namespaces are pre-created by operators; target namespaces need a Role and RoleBinding. The supplied manifests grant access only to generated-dev deployments and services, with no namespace or secret permissions. scaffoldops-dev must already exist.

Events, keyed by generationRequestId:

```json
{"generationRequestId":"a4bc394a-0888-494d-9769-30e2333a341d","name":"hello","artifactRef":"s3://artifacts/hello.zip","imageRef":"docker.io/example/hello:latest","namespace":"generated-dev","replicas":1,"requestedAt":"2026-10-08T12:00:00Z"}
```

undeployment-requested uses generationRequestId, name, namespace, requestedAt only. Unknown fields are ignored. Invalid messages are logged and discarded without Kubernetes writes. Operational/API transport errors retry indefinitely. Kubernetes failures report DEPLOYMENT_FAILED, permitting an explicit API retry. Callbacks require authentication; configure OAuth client credentials (GENERATOR_API_TOKEN_URL, GENERATOR_API_CLIENT_ID, GENERATOR_API_CLIENT_SECRET) or a development GENERATOR_API_TOKEN. The worker checks the API status/namespace before applying events; deleted or superseded requests are skipped. Callback transport/server failures are retried; 404/409 responses acknowledge a deleted or superseded lifecycle. There is no distributed transaction across Kafka, API and Kubernetes; a callback can still race a newer lifecycle operation. Cancellation during DEPLOYING is intentionally unsupported.

Resource names are a sanitized prefix (30 characters) plus the full UUID without dashes, at most 63 characters. Deployment and ClusterIP Service have app.kubernetes.io/name, app.kubernetes.io/managed-by=scaffoldops and scaffoldops.io/request-id labels. Updates use kubectl apply, refuse resources with another owner, and wait up to 120 seconds for rollout readiness (TCP port 8080). Undeploy deletes only resources with both ownership labels; missing resources succeed. Artifacts/images/DB are never deleted by this worker. Permanent asset cleanup remains generator-worker's responsibility. This worker also consumes artifact-cleanup-requested (configurable app.kafka.topics.artifact-cleanup-requested / ARTIFACT_CLEANUP_REQUESTED_TOPIC) in its own Kafka group to remove Kubernetes resources using requestId, name, deploymentNamespace and deletedAt. Cleanup skips events without a namespace and does not callback after DB deletion.

Configuration: app.kafka.topics.deployment-requested and app.kafka.topics.undeployment-requested (environment DEPLOYMENT_REQUESTED_TOPIC / UNDEPLOYMENT_REQUESTED_TOPIC), app.generator-api.base-url (GENERATOR_API_BASE_URL), app.kubernetes.container-port (GENERATED_CONTAINER_PORT, default 8080), app.kubernetes.kubectl (default kubectl). Kafka defaults to localhost:9092, override KAFKA_BOOTSTRAP_SERVERS.

Local/minikube: install kubectl matching the cluster, select the intended context, pre-create generated-dev, provide Kafka and API URLs and credentials, then mvn spring-boot:run. In-cluster: build the Dockerfile, publish/load the image, configure deployment-worker-oauth Secret with token-url and client-secret, then apply k8s/deployment. Adapt the worker namespace, image and Kafka address to your platform. Provision equivalent target RoleBindings for additional namespaces. No cluster-admin binding is needed.

Deploy via POST /generation-requests/{id}/deployment with {"namespace":"generated-dev","replicas":1}; observe DEPLOYING → DEPLOYED or DEPLOYMENT_FAILED. DELETE the same subresource gives UNDEPLOYING → NOT_DEPLOYED or DEPLOYMENT_FAILED. Namespace and assets remain for redeployment. DELETE /generation-requests/{id} permanently removes the request/assets.

Test a deployed hello-world service:

```sh
kubectl -n generated-dev get services -l scaffoldops.io/request-id=REQUEST_UUID
kubectl -n generated-dev port-forward service/RESOURCE_NAME 8088:8080
curl http://localhost:8088/hello
```

Validation: mvn clean test; kubectl kustomize k8s/deployment. Unit tests mock kubectl/API; a live rollout requires an accessible image, cluster, Kafka and authenticated API.

Deployment publication waits for broker acknowledgement before the API commits. Events observed before that commit retry based on timestamps.updatedAt. PostgreSQL/Kafka still have no outbox; a commit failure after publish requires reconciliation.
