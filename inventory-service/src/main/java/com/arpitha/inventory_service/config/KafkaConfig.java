package com.arpitha.inventory_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.mapping.DefaultJackson2JavaTypeMapper;
import org.springframework.kafka.support.mapping.Jackson2JavaTypeMapper;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    /** DeadLetterPublishingRecoverer's default: failed records go to "<topic>.DLT". */
    private static final String DLT_SUFFIX = ".DLT";
    private static final int TOPIC_PARTITIONS = 3;
    private static final int MAX_RETRIES = 3;
    private static final long FIRST_RETRY_DELAY_MS = 1000L;
    private static final double RETRY_DELAY_MULTIPLIER = 2.0;

    /**
     * Listeners receive raw JSON bytes and convert them to the type declared on the
     * @KafkaListener method, so this service never needs the producer's event classes.
     */
    @Bean
    public RecordMessageConverter kafkaMessageConverter(ObjectMapper objectMapper) {
        ByteArrayJsonMessageConverter converter = new ByteArrayJsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setTypeMapper(typeMapper);
        return converter;
    }

    /**
     * Retries a failed record 3 times with exponential backoff (1 s, 2 s, 4 s), then publishes the original bytes
     * to "<topic>.DLT". Unreadable messages go straight to the DLT without retries.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        KafkaTemplate<String, byte[]> deadLetterTemplate =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(FIRST_RETRY_DELAY_MS);
        backOff.setMultiplier(RETRY_DELAY_MULTIPLIER);

        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(deadLetterTemplate), backOff);
    }

    /**
     * Every topic this service touches: the ones it publishes, the ones it consumes, and the
     * dead-letter topics for those. Declaring consumed topics too means whichever service starts
     * first creates them with the right partition count, before any listener subscribes.
     * (Otherwise a consumer could auto-create a 1-partition topic, which the producer later grows,
     * leaving the consumer blind to the new partitions for minutes.)
     */
    @Bean
    public KafkaAdmin.NewTopics inventoryServiceTopics(KafkaTopicProperties topics) {
        return new KafkaAdmin.NewTopics(
                topic(topics.getInventoryReserved()),
                topic(topics.getInventoryReservationFailed()),
                topic(topics.getOrderCreated()),
                topic(topics.getOrderCancelled()),
                topic(topics.getOrderCreated() + DLT_SUFFIX),
                topic(topics.getOrderCancelled() + DLT_SUFFIX));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(TOPIC_PARTITIONS).replicas(1).build();
    }
}
