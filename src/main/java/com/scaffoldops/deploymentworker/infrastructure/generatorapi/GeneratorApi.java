package com.scaffoldops.deploymentworker.infrastructure.generatorapi;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import com.scaffoldops.deploymentworker.application.port.out.GenerationRequestStatusPort;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
@Component
public class GeneratorApi implements GenerationRequestStatusPort {
 private final RestClient client;private final String tokenUrl,clientId,clientSecret,staticToken;
 public GeneratorApi(RestClient.Builder builder,@Value("${app.generator-api.base-url}") String url,
 @Value("${app.generator-api.token-url:}") String tokenUrl,@Value("${app.generator-api.client-id:deployment-worker}") String clientId,
 @Value("${app.generator-api.client-secret:}") String clientSecret,@Value("${app.generator-api.token:}") String staticToken) {
  var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build());
  factory.setReadTimeout(java.time.Duration.ofSeconds(30));
  this.client=builder.requestFactory(factory).baseUrl(url).build();this.tokenUrl=tokenUrl;this.clientId=clientId;this.clientSecret=clientSecret;this.staticToken=staticToken;
 }
 private String token() {
  if(!staticToken.isBlank()) return staticToken;
  if(tokenUrl.isBlank() || clientSecret.isBlank()) throw new IllegalStateException("Configure generator-api OAuth client credentials or token");
  var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","client_credentials");form.add("client_id",clientId);form.add("client_secret",clientSecret);
  JsonNode result=client.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
  if(result==null || result.path("access_token").asText().isBlank()) throw new IllegalStateException("OAuth token missing");
  return result.path("access_token").asText();
 }
 public boolean pending(DeploymentEvent e,boolean deploy) {
  try {
   JsonNode request=client.get().uri("/generation-requests/{id}",e.generationRequestId()).headers(h->h.setBearerAuth(token())).retrieve().body(JsonNode.class);
   if (request == null) throw new IllegalStateException("Empty generation request response");
   String updatedAt = request.path("timestamps").path("updatedAt").asText();
   if (!updatedAt.isBlank()) {
    var updated = java.time.OffsetDateTime.parse(updatedAt);
    // Allow database timestamp precision rounding; otherwise identify the exact operation.
    if (updated.isBefore(e.requestedAt().minusNanos(1000000)))
     throw new IllegalStateException("Deployment transaction is not yet visible");
    if (updated.isAfter(e.requestedAt().plusNanos(1000000))) return false;
   }
   return e.namespace().equals(request.path("deployment").path("namespace").asText())
    && (deploy ? "DEPLOYING" : "UNDEPLOYING").equals(request.path("deployment").path("status").asText());
  } catch(org.springframework.web.client.HttpClientErrorException.NotFound ex) { return false; }
 }
 public void callback(DeploymentEvent e,String status,String message) {
  try { client.patch().uri("/internal/generation-requests/{id}/deployment-status",e.generationRequestId()).headers(h->h.setBearerAuth(token()))
   .contentType(MediaType.APPLICATION_JSON).body(Map.of("deploymentStatus",status,"namespace",e.namespace(),"message",message)).retrieve().toBodilessEntity();
  } catch(org.springframework.web.client.HttpClientErrorException ex) {
   if(ex.getStatusCode().value()!=404 && ex.getStatusCode().value()!=409) throw ex;
  }
 }
}
