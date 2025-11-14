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

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

class ConfigManager {
    private static Properties properties = new Properties();

    static {
        // For the moment, let's use a full path to the .env file. This obviously won't work when
        // it's run in a different context, but I can work out how to do that later on.
        try (InputStream input = new FileInputStream(
                "/Users/tony.ibbs/sw/aiven/project-celtic/kafka-streams-in-celtic/datacamp-tutorial/.env"
        )) {
            properties.load(input);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load .env file", e);
        }
    }

    public static String get(String key) {
        return properties.getProperty(key);
    }
}

public class WordCountApp {

   public static void main(String[] args) {
       // 1. Create Kafka Streams configuration
       Properties config = new Properties();
       config.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-application");
       config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, ConfigManager.get("KAFKA_SERVICE_URI"));
       config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
       config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

       // Security settings.
       // a. These settings must match the security settings of the secure Kafka cluster.
       // b. The SSL trust store and key store files must be locally accessible to the application.
       config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
       config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ConfigManager.get("SSL_TRUSTSTORE_LOCATION"));
       config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, ConfigManager.get("SSL_TRUSTSTORE_PASSWORD"));
       config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ConfigManager.get("SSL_KEYSTORE_LOCATION"));
       config.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, ConfigManager.get("SSL_KEYSTORE_PASSWORD"));
       config.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, ConfigManager.get("SSL_KEY_PASSWORD"));

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
       System.out.println(streams.toString());
   }
}