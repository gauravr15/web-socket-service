package com.odin.web_socket_service.config;

import com.odin.web_socket_service.dto.NotificationDTO;
import com.odin.web_socket_service.dto.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer Configuration for sending notification messages.
 */
@Slf4j
@Configuration
@EnableKafka
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.acks:all}")
    private String acks;

    @Value("${spring.kafka.producer.retries:3}")
    private int retries;

    @Value("${spring.kafka.producer.batch-size:16384}")
    private int batchSize;

    @Value("${spring.kafka.producer.linger-ms:10}")
    private int lingerMs;

    /**
     * Producer factory for NotificationMessage (used by KafkaNotificationService)
     */
    @Bean
    public ProducerFactory<String, NotificationMessage> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, acks);
        configProps.put(ProducerConfig.RETRIES_CONFIG, retries);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        log.info("Initializing Kafka ProducerFactory with bootstrapServers={}, acks={}, retries={}", 
                bootstrapServers, acks, retries);
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Kafka template for NotificationMessage (used by KafkaNotificationService)
     */
    @Bean
    public KafkaTemplate<String, NotificationMessage> kafkaTemplate() {
        KafkaTemplate<String, NotificationMessage> template = new KafkaTemplate<>(producerFactory());
        template.setDefaultTopic("notification-events");
        log.info("KafkaTemplate configured with default topic: notification-events");
        return template;
    }

    /**
     * Producer factory for NotificationDTO (used by NotificationUtility)
     */
    @Bean
    public ProducerFactory<String, NotificationDTO> notificationDTOProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, acks);
        configProps.put(ProducerConfig.RETRIES_CONFIG, retries);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        log.info("Initializing Kafka NotificationDTO ProducerFactory with bootstrapServers={}", bootstrapServers);
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Kafka template for NotificationDTO (used by NotificationUtility)
     */
    @Bean
    public KafkaTemplate<String, NotificationDTO> notificationDTOKafkaTemplate() {
        KafkaTemplate<String, NotificationDTO> template = new KafkaTemplate<>(notificationDTOProducerFactory());
        template.setDefaultTopic("sample-message-topic");
        log.info("NotificationDTO KafkaTemplate configured with default topic: sample-message-topic");
        return template;
    }
}
