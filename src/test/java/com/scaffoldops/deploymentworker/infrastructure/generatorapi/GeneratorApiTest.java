package com.scaffoldops.deploymentworker.infrastructure.generatorapi;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class GeneratorApiTest {
 @Test void authenticatesChecksStateAndSendsCallback() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var requestBody=new AtomicReference<String>();var authorization=new AtomicReference<String>();
  var apiResponse=new AtomicReference<String>("{\"deployment\":{\"namespace\":\"generated-dev\",\"status\":\"DEPLOYING\"},\"timestamps\":{\"updatedAt\":\"2026-10-08T12:00:00Z\"}}");
  var callbackStatus=new java.util.concurrent.atomic.AtomicInteger(204);
  server.createContext("/", exchange->{
   authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
   if(exchange.getRequestMethod().equals("PATCH")) {
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
    exchange.sendResponseHeaders(callbackStatus.get(),-1);
   } else { byte[] response=apiResponse.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response); }
   exchange.close();
  });server.start();
  try {
   var api=new GeneratorApi(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort(),"","","","test-token");
   var e=new DeploymentEvent(UUID.randomUUID(),"hello","s3://a/b","image","generated-dev",1,OffsetDateTime.parse("2026-10-08T12:00:00.000000123Z"));
   assertThat(api.pending(e,true)).isTrue(); // Database precision does not cause endless retries.
   api.callback(e,"DEPLOYED","Ready");assertThat(authorization.get()).isEqualTo("Bearer test-token");
   var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody.get());
   assertThat(body.path("namespace").asText()).isEqualTo(e.namespace());assertThat(body.path("deploymentStatus").asText()).isEqualTo("DEPLOYED");
   callbackStatus.set(503);assertThatThrownBy(()->api.callback(e,"DEPLOYED","Ready")).isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
   callbackStatus.set(409);api.callback(e,"DEPLOYED","Ready");
   apiResponse.set("{\"deployment\":{\"status\":\"NOT_DEPLOYED\"},\"timestamps\":{\"updatedAt\":\"2026-10-08T11:59:00Z\"}}");
   assertThatThrownBy(()->api.pending(e,true)).hasMessageContaining("not yet visible");
   apiResponse.set("{\"deployment\":{\"status\":\"NOT_DEPLOYED\"},\"timestamps\":{\"updatedAt\":\"2026-10-08T12:01:00Z\"}}");
   assertThat(api.pending(e,true)).isFalse();
   apiResponse.set("{\"deployment\":{\"namespace\":\"generated-dev\",\"status\":\"DEPLOYING\"},\"timestamps\":{\"updatedAt\":\"2026-10-08T12:01:00Z\"}}");
   assertThat(api.pending(e,true)).isFalse(); // Old deploy cannot overwrite a newer retry.
  } finally {server.stop(0);}
 }
 @Test void authenticatesWithClientCredentialsAndAcknowledgesDeletedRequests() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var tokenForm=new AtomicReference<String>();
  var authorization=new AtomicReference<String>();
  server.createContext("/token", exchange->{
   tokenForm.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   byte[] response="{\"access_token\":\"oauth-token\"}".getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");
   exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
  });
  server.createContext("/", exchange->{
   authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
   exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(404,-1);exchange.close();
  });
  server.start();
  try {
   String url="http://127.0.0.1:"+server.getAddress().getPort();
   var api=new GeneratorApi(RestClient.builder(),url,url+"/token","deployment-worker","secret","");
   var e=new DeploymentEvent(UUID.randomUUID(),"hello","s3://a/b","image","generated-dev",1,OffsetDateTime.now());
   assertThat(api.pending(e,true)).isFalse();
   api.callback(e,"DEPLOYED","Ready");
   assertThat(authorization.get()).isEqualTo("Bearer oauth-token");
   assertThat(tokenForm.get()).contains("grant_type=client_credentials","client_id=deployment-worker","client_secret=secret");
  } finally {server.stop(0);}
 }

}
