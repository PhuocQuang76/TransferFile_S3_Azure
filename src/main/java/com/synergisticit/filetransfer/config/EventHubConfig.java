package com.synergisticit.filetransfer.config;

import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerAsyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Slf4j
@Configuration
@ConditionalOnProperty(name = "azure.eventhub.enabled", havingValue = "true")
//This is a safety feature. It ensures Spring only loads this configuration if
// azure.eventhub.enabled is set to true in your properties file. If it is disabled
// or missing, the application can still start up normally without crashing.
public class EventHubConfig {

    //Defines a Spring @Bean that creates and manages the asynchronous producer client used to send messages to Azure.
    @Bean
    public EventHubProducerAsyncClient eventHubProducerAsyncClient(
            @Value("${azure.eventhub.connection-string}") String connectionString,
            @Value("${azure.eventhub.event-hub-name}") String eventHubName) {

        log.info("Creating Event Hubs producer for hub [{}]", eventHubName);

        // The send-only policy is defined at the NAMESPACE level, so its connection string carries
        // no EntityPath and the hub name must be supplied separately.
        return new EventHubClientBuilder()
                .connectionString(connectionString, eventHubName)
                .buildAsyncProducerClient();

        /*
        Asynchronous & Non-Blocking
        File #1 finishes uploading, and your code calls producer.send(batch).
        The Azure SDK hands the event packet to the operating system's network
         socket and immediately returns a reactive Mono wrapper.

        The thread is instantly freed to go work on the next incoming file
         chunk or stream. It does not waste a single millisecond sitting idle.

        Behind the scenes, when Azure finally responds over the network,
         the reactive engine (Netty) picks up the notification and completes
         the Mono seamlessly in the background.
         */
    }
}
