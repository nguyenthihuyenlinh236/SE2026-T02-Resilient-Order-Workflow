package com.example.orderworkflow.order.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Worker is opt-in until expiry/late-response coordination is implemented. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "order.worker.enabled", havingValue = "true")
public class ReserveWorkerConfiguration {
    @Bean
    InventoryClient inventoryClient(RestClient.Builder builder, ObjectMapper json,
            @Value("${inventory.base-url}") String baseUrl,
            @Value("${inventory.connect-timeout:2s}") Duration connectTimeout,
            @Value("${inventory.read-timeout:3s}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new InventoryClient(builder.baseUrl(baseUrl).requestFactory(requestFactory).build(), json);
    }

    @Bean
    ReserveStepWorker reserveStepWorker(InventoryClient client, com.example.orderworkflow.order.repository.SagaRepository saga,
            ReserveResultHandler results, @Value("${order.worker.lease:10s}") Duration lease) {
        return new ReserveStepWorker(client, saga, results, lease);
    }
}
