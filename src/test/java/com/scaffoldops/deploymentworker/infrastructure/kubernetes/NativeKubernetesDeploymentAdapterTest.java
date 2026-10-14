package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.deploymentworker.application.port.out.GenerationRequestStatusPort;
import com.scaffoldops.deploymentworker.application.service.DeploymentLifecycleService;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import com.sun.net.httpserver.HttpServer;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeKubernetesDeploymentAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DeploymentEvent event = new DeploymentEvent(
            UUID.fromString("a4bc394a-0888-494d-9769-30e2333a341d"), "Hello World!", "s3://a/b",
            "docker.io/a/b:tag", "generated-dev", 1, OffsetDateTime.now());
    private final KubernetesResourceFactory factory = new KubernetesResourceFactory(8080);
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private NativeKubernetesDeploymentAdapter adapter;
    private boolean existing;
    private String foreignKind;
    private boolean ready = true;
    private boolean staleGeneration;
    private boolean oldReplica;
    private boolean deleting;
    private boolean missingDelete;
    private boolean forbidden;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String kind = path.contains("deployments") ? "Deployment" : "Service";
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(method, path, exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            int code = 200;
            String response;
            if (forbidden) {
                code = 403;
                response = "{\"kind\":\"Status\",\"message\":\"forbidden\"}";
            } else if (method.equals("DELETE")) {
                deleting = true;
                code = missingDelete ? 404 : 200;
                // Client 23.0.0 expects V1Service for Service deletion and V1Status for Deployment deletion.
                response = !missingDelete && kind.equals("Service")
                        ? resource(kind, false)
                        : "{\"kind\":\"Status\",\"status\":\"Success\"}";
            } else if (method.equals("POST") || method.equals("PATCH")) {
                response = resource(kind, false);
            } else if (path.endsWith("/deployments") || path.endsWith("/services")) {
                response = "{\"apiVersion\":\"" + (kind.equals("Deployment") ? "apps/v1" : "v1")
                        + "\",\"kind\":\"" + kind + "List\",\"items\":"
                        + (existing && !deleting ? "[" + resource(kind, false) + "]" : "[]") + "}";
            } else if (!existing && requests.stream().noneMatch(r -> r.method.equals("POST"))) {
                code = 404;
                response = "{\"kind\":\"Status\",\"reason\":\"NotFound\"}";
            } else {
                response = resource(kind, true);
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = new ApiClient().setBasePath("http://127.0.0.1:" + server.getAddress().getPort());
        adapter = new NativeKubernetesDeploymentAdapter(new AppsV1Api(client), new CoreV1Api(client), factory,
                Duration.ofMillis(150), Duration.ofMillis(5));
    }

    @AfterEach
    void stop() { server.stop(0); }

    private String resource(String kind, boolean includeStatus) {
        var client = new ApiClient();
        var node = json.createObjectNode();
        try {
            node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(client.getJSON().serialize(
                    kind.equals("Deployment") ? factory.deployment(event) : factory.service(event)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
        var metadata = (com.fasterxml.jackson.databind.node.ObjectNode) node.get("metadata");
        metadata.put("resourceVersion", "7").put("generation", 2).put("uid", "resource-uid");
        if (kind.equals(foreignKind)) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.get("labels"))
                    .put("scaffoldops.io/request-id", UUID.randomUUID().toString());
        }
        if (kind.equals("Deployment") && includeStatus) {
            node.putObject("status").put("observedGeneration", staleGeneration ? 1 : 2)
                    .put("replicas", oldReplica ? 2 : 1).put("updatedReplicas", ready ? 1 : 0)
                    .put("readyReplicas", ready ? 1 : 0).put("availableReplicas", ready ? 1 : 0);
        }
        return node.toString();
    }

    @Test
    void createsDeploymentAndServiceAndWaitsForRollout() throws Exception {
        adapter.deploy(event);
        var writes = requests.stream().filter(r -> r.method.equals("POST")).toList();
        assertThat(writes).hasSize(2);
        var deployment = json.readTree(writes.get(0).body);
        assertThat(deployment.at("/metadata/name").asText()).isEqualTo(event.resourceName());
        assertThat(deployment.at("/metadata/namespace").asText()).isEqualTo("generated-dev");
        assertThat(deployment.at("/metadata/labels")).isEqualTo(json.valueToTree(factory.labels(event)));
        assertThat(deployment.at("/spec/template/metadata/labels")).isEqualTo(deployment.at("/metadata/labels"));
        assertThat(deployment.at("/spec/replicas").asInt()).isEqualTo(1);
        assertThat(deployment.at("/spec/template/spec/containers/0/image").asText()).isEqualTo(event.imageRef());
        assertThat(deployment.at("/spec/template/spec/containers/0/readinessProbe/tcpSocket/port").asInt()).isEqualTo(8080);
        var service = json.readTree(writes.get(1).body);
        assertThat(service.at("/metadata/labels")).isEqualTo(deployment.at("/metadata/labels"));
        assertThat(service.at("/spec/selector")).isEqualTo(deployment.at("/spec/selector/matchLabels"));
        assertThat(service.at("/spec/type").asText()).isEqualTo("ClusterIP");
        assertThat(service.at("/spec/ports/0/targetPort").asInt()).isEqualTo(8080);
        assertThat(requests.get(requests.size() - 1).path).endsWith("deployments/" + event.resourceName());
    }

    @Test
    void patchesOwnedResourcesWithOptimisticConcurrencyAndPreservesAllocatedServiceFields() throws Exception {
        existing = true;
        adapter.deploy(event);
        var patches = requests.stream().filter(r -> r.method.equals("PATCH")).toList();
        assertThat(patches).hasSize(2);
        assertThat(requests).noneMatch(r -> r.method.equals("POST"));
        for (var patch : patches) {
            assertThat(patch.contentType).isEqualTo("application/strategic-merge-patch+json");
            assertThat(json.readTree(patch.body).at("/metadata/resourceVersion").asText()).isEqualTo("7");
        }
        assertThat(json.readTree(patches.get(0).body).at("/spec/template/spec/containers/0/image").asText())
                .isEqualTo(event.imageRef());
        assertThat(json.readTree(patches.get(1).body).at("/spec/clusterIP").isMissingNode()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Deployment", "Service"})
    void rejectsForeignResourceBeforeAnyWrite(String kind) {
        existing = true;
        foreignKind = kind;
        assertThatThrownBy(() -> adapter.deploy(event)).hasMessageContaining("another owner");
        assertThat(requests).allMatch(r -> r.method.equals("GET"));
    }

    @Test
    void deletesByOwnershipLabelsAndWaitsForRemoval() throws Exception {
        existing = true;
        adapter.undeploy(event);
        var deletes = requests.stream().filter(r -> r.method.equals("DELETE")).toList();
        assertThat(deletes).hasSize(2);
        for (var deletion : deletes) {
            assertThat(json.readTree(deletion.body).at("/preconditions/uid").asText()).isEqualTo("resource-uid");
            assertThat(json.readTree(deletion.body).at("/preconditions/resourceVersion").asText()).isEqualTo("7");
        }
        assertThat(requests.stream().filter(r -> r.method.equals("GET"))).allSatisfy(r ->
                assertThat(r.query).isEqualTo("labelSelector=scaffoldops.io/request-id=" + event.generationRequestId()
                        + ",app.kubernetes.io/managed-by=scaffoldops"));
    }

    @Test
    void emptyListsAreSuccessfulUndeploy() {
        adapter.undeploy(event);
        assertThat(requests).noneMatch(r -> r.method.equals("DELETE"));
    }

    @Test
    void resourcesDisappearingBeforeDeleteAreSuccessful() {
        existing = true;
        missingDelete = true;
        assertThatCode(() -> adapter.undeploy(event)).doesNotThrowAnyException();
        assertThat(requests.stream().filter(r -> r.method.equals("DELETE"))).hasSize(2);
    }

    @Test
    void readinessTimeoutMapsToDeploymentFailed() {
        ready = false;
        var status = mock(GenerationRequestStatusPort.class);
        when(status.pending(event, true)).thenReturn(true);
        new DeploymentLifecycleService(adapter, status).deploy(event);
        verify(status).callback(event, "DEPLOYMENT_FAILED", "Kubernetes Deployment readiness timed out");
    }

    @Test
    void oldObservedGenerationDoesNotCountAsReady() {
        staleGeneration = true;
        assertThatThrownBy(() -> adapter.deploy(event)).hasMessageContaining("readiness timed out");
    }

    @Test
    void oldReplicasMustFinishTerminatingBeforeSuccess() {
        oldReplica = true;
        assertThatThrownBy(() -> adapter.deploy(event)).hasMessageContaining("readiness timed out");
    }

    @Test
    void authorizationFailureIsNotTreatedAsMissing() {
        forbidden = true;
        assertThatThrownBy(() -> adapter.deploy(event)).hasMessageContaining("HTTP 403");
        assertThatThrownBy(() -> adapter.undeploy(event)).hasMessageContaining("HTTP 403");
    }

    private record Request(String method, String path, String query, String contentType, String body) {}
}
