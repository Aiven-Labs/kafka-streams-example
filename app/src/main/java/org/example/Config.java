package org.example;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private static Map<String, String> serdeConfig = new HashMap<String, String>();

    /** Gather our `-D` command line switch values */
    public static Properties getConfig() {
        // We put them all into one configuration, even though they really fall into three groups
        String kafkaServiceUri = System.getProperty("KAFKA_SERVICE_URI");
        String sslTruststoreLocation = System.getProperty("SSL_TRUSTSTORE_LOCATION");
        String sslKeystoreLocation = System.getProperty("SSL_KEYSTORE_LOCATION");
        String passwordForStore = System.getProperty("PASSWORD_FOR_STORE");
        String schemaRegistryUrl = System.getProperty("SCHEMA_REGISTRY_URL");
        // We have a sensible default for the schema registry user, so provide it
        String schemaRegistryUserName = System.getProperty("SCHEMA_REGISTRY_USERNAME", "avnadmin");
        String schemaRegistryPassword = System.getProperty("SCHEMA_REGISTRY_PASSWORD");
        // We have defaults for our topic names as well
        String inputTopic = System.getProperty("INPUT_TOPIC", "logistics_data_gen");
        String outputTopic = System.getProperty("OUTPUT_TOPIC", "logistics_data_delivered");

        if (kafkaServiceUri == null
                || sslTruststoreLocation == null
                || sslKeystoreLocation == null
                || passwordForStore == null
                || schemaRegistryUrl == null
                || schemaRegistryUserName == null
                || schemaRegistryPassword == null
                || inputTopic == null
                || outputTopic == null) {
            if (kafkaServiceUri == null) log.error("Missing value for -DKAFKA_SERVICE_URI");
            if (sslTruststoreLocation == null) log.error("Missing value for -DSSL_TRUSTSTORE_LOCATION");
            if (sslKeystoreLocation == null) log.error("Missing value for -DSSL_KEYSTORE_LOCATION");
            if (passwordForStore == null) log.error("Missing value for -DPASSWORD_FOR_STORE");
            if (schemaRegistryUrl == null) log.error("Missing value for -DSCHEMA_REGISTRY_URL");
            if (schemaRegistryUserName == null) log.error("Missing value for -DSCHEMA_REGISTRY_USERNAME");
            if (schemaRegistryPassword == null) log.error("Missing value for -DSCHEMA_REGISTRY_PASSWORD");
            if (inputTopic == null) log.error("Missing value for -DINPUT_TOPIC");
            if (outputTopic == null) log.error("Missing value for -DOUTPUT_TOPIC");
            System.exit(1);
        }

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "json-filter-application");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServiceUri);

        // We're not particularly interested in the message key, so leave it as a string
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        // We'll set the actual value Serdes we want later on
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);

        // Security settings.
        // a. These settings must match the security settings of the secure Kafka cluster.
        // b. The SSL trust store and key store files must be locally accessible to the application.
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, sslTruststoreLocation);
        config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, passwordForStore);
        config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, sslKeystoreLocation);
        config.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, passwordForStore);
        config.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, passwordForStore);

        String schemaRegistryBasicAuthUserInfo = schemaRegistryUserName + ":" + schemaRegistryPassword;

        // Schema registry values - we create the `serdeConfig` here since here is where we have
        // the command line values, but we don't need to put those values into the `config`
        serdeConfig.put("schema.registry.url", schemaRegistryUrl);
        serdeConfig.put("schema.registry.basic.auth.credentials.source", "USER_INFO");
        serdeConfig.put("schema.registry.basic.auth.user.info", schemaRegistryBasicAuthUserInfo);

        // When we're in the SpecificFilterApp use case, we also need those same values in the main config as well
        config.put("schema.registry.url", schemaRegistryUrl);
        config.put("schema.registry.basic.auth.credentials.source", "USER_INFO");
        config.put("schema.registry.basic.auth.user.info", schemaRegistryBasicAuthUserInfo);

        // Topic names
        config.put("input.topic.name", inputTopic);
        config.put("output.topic.name", outputTopic);

        return config;
    }

    public static Map<String, String> getSerdeConfig(Properties config) {
        return serdeConfig;
    }

}