package com.scaffoldops.deploymentworker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
@Configuration
public class KafkaConfiguration {
 @Bean DefaultErrorHandler errorHandler() {
  var handler=new DefaultErrorHandler(new FixedBackOff(5000,FixedBackOff.UNLIMITED_ATTEMPTS));
  handler.addNotRetryableExceptions(IllegalArgumentException.class);return handler;
 }
}
