package dev.cabwatch;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfiguration {
  @Bean
  NewTopic topic(@Value("${cabwatch.partitions}") int p, @Value("${cabwatch.replicas}") int r) {
    return TopicBuilder.name(Pipeline.TOPIC).partitions(p).replicas(r).build();
  }

  // Dependency outages retry indefinitely. History records are never silently discarded.
  @Bean
  CommonErrorHandler errorHandler() {
    return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> liveFactory(
      ConsumerFactory<String, String> consumers,
      CommonErrorHandler errors,
      @Value("${spring.kafka.listener.concurrency}") int concurrency) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
    f.setConsumerFactory(consumers);
    f.setConcurrency(concurrency);
    f.setBatchListener(true);
    f.setCommonErrorHandler(errors);
    f.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
    return f;
  }
}
