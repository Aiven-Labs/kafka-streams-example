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

    private static boolean isNullOrEmpty(String s) {
        return s == null || s.isBlank();
    }

    /** Gather our `-D` command line switch values */
    public static Properties getConfig(String defaultInputTopic, String defaultOutputTopic) {
        // We put them all into one configuration, even though they really fall into three groups
        // Kafka access
        String kafkaServiceUri = System.getProperty("KAFKA_SERVICE_URI");
        String caPemContents = System.getProperty("CA_PEM_CONTENTS");
        String serviceCertContents = System.getProperty("SERVICE_CERT_CONTENTS");
        String serviceKeyContents = System.getProperty("SERVICE_KEY_CONTENTS");
        String schemaRegistryUrl = System.getProperty("SCHEMA_REGISTRY_URL");
        // Schema repository access
        String schemaRegistryUserName = System.getProperty("SCHEMA_REGISTRY_USERNAME");
        String schemaRegistryPassword = System.getProperty("SCHEMA_REGISTRY_PASSWORD");
        // Input and output topics
        String inputTopic = System.getProperty("INPUT_TOPIC");
        String outputTopic = System.getProperty("OUTPUT_TOPIC");

        // Because we know that the system properties are being populated from shell
        // environment variables in the `run.sh` that calls us, it's possible that they
        // might be absent/unset (resulting in `null`), or the empty string (or perhaps
        // even a string with spaces in it). We want to treat both the same way.
        // So we shan't use the `default` argument to System.getProperty. And to be
        // honest, since we're going to be checking for `null` values anyway, that's
        // not a big loss.

        // We could do something clever here with streams, but honestly it's simpler
        // just to do the linearised version. And we do want an error message for each
        // value that is absent - not just report one and then give up.
        boolean giveUp = false;

        if (isNullOrEmpty(kafkaServiceUri)) {
            log.error("Missing value for -DKAFKA_SERVICE_URI");
            giveUp = true;
        }
        if (isNullOrEmpty(caPemContents)) {
            log.error("Missing value for -DCA_PEM_CONTENTS");
            giveUp = true;
        }
        if (isNullOrEmpty(serviceCertContents)) {
            log.error("Missing value for -DSERVICE_CERT_CONTENTS");
            giveUp = true;
        }
        if (isNullOrEmpty(serviceKeyContents)) {
            log.error("Missing value for -DSERVICE_KEY_CONTENTS");
            giveUp = true;
        }
        if (isNullOrEmpty(schemaRegistryUrl)) {
            log.error("Missing value for -DSCHEMA_REGISTRY_URL");
            giveUp = true;
        }
        if (isNullOrEmpty(schemaRegistryPassword)) {
            log.error("Missing value for -DSCHEMA_REGISTRY_PASSWORD");
            giveUp = true;
        }
        if (giveUp) {
            System.exit(1);
        }

        if (isNullOrEmpty(schemaRegistryUserName)) {
            schemaRegistryUserName = "avnadmin";
        }
        if (isNullOrEmpty(inputTopic)) {
            inputTopic = defaultInputTopic;
        }
        if (isNullOrEmpty(outputTopic)) {
            outputTopic = defaultOutputTopic;
        }

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "json-filter-application");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServiceUri);

        // We're not particularly interested in the message key, so leave it as a string
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        // We'll set the actual value Serdes we want later on
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);

        // Security settings.
        // These settings must match the security settings of the secure Kafka cluster.
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        config.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
        config.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, serviceCertContents);
        config.put(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, serviceKeyContents);
        config.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
        config.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, caPemContents);

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