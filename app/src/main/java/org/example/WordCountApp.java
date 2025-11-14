package org.example;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;

import java.util.Arrays;
import java.util.Properties;

public class WordCountApp {

   public static void main(String[] args) {

       System.out.println("Arguments:");
       System.out.println(System.getProperty("KAFKA_SERVICE_URI"));
       System.out.println(System.getProperty("SSL_TRUSTSTORE_LOCATION"));
       System.out.println(System.getProperty("SSL_KEYSTORE_LOCATION"));
       System.out.println(System.getProperty("PASSWORD_FOR_STORE"));

       // 1. Create Kafka Streams configuration
       Properties config = new Properties();
       config.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-application");
       config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("KAFKA_SERVICE_URI"));
       config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
       config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

       // Security settings.
       // a. These settings must match the security settings of the secure Kafka cluster.
       // b. The SSL trust store and key store files must be locally accessible to the application.
       config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
       config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, System.getProperty("SSL_TRUSTSTORE_LOCATION"));
       config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, System.getProperty("PASSWORD_FOR_STORE"));
       config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, System.getProperty("SSL_KEYSTORE_LOCATION"));
       config.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, System.getProperty("PASSWORD_FOR_STORE"));
       config.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, System.getProperty("PASSWORD_FOR_STORE"));

       // 2. Create a StreamsBuilder - the factory for all stream operations
       StreamsBuilder builder = new StreamsBuilder();

       // 3. Read from the source topic: "text-input"
       KStream<String, String> textLines = builder.stream("text-input");

       // 4. Implement word count logic
       KTable<String, Long> wordCounts = textLines
           // Split each text line into words
           .flatMapValues(value -> Arrays.asList(value.toLowerCase().split("\\W+")))
           // Ensure the words are not empty
           .filter((key, value) -> !value.isEmpty())
           // We want to count words, so we use the word as the key
           .groupBy((key, value) -> value)
           // Count occurrences of each word
           .count();

       // 5. Write the results to the output topic
       wordCounts.toStream().to("word-count-output", Produced.with(Serdes.String(), Serdes.Long()));

       // 6. Create and start the Kafka Streams application
       KafkaStreams streams = new KafkaStreams(builder.build(), config);

       // Add shutdown hook to gracefully close the streams application
       Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

       // Start the application
       streams.start();

       // Print the topology description
       System.out.println(streams);
   }
}
