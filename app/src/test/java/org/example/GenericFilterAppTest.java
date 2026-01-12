package org.example;

import static org.junit.jupiter.api.Assertions.*;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.example.GenericFilterApp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;






/*
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Properties;

public class TestProducer {
    public static void main(String[] args) {
        MockSchemaRegistryClient mockClient = new MockSchemaRegistryClient();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", "mock://my-scope-name"); // Using mock URL
        props.put("schema.registry.client", mockClient); // Set mock client

        KafkaProducer<String, MyAvroRecord> producer = new KafkaProducer<>(props);

        MyAvroRecord avroRecord = new MyAvroRecord.Builder()
                                     .setName("test")
                                     .setAge(30)
                                     .build();

        ProducerRecord<String, MyAvroRecord> record = new ProducerRecord<>("my-topic", avroRecord);

        try {
            producer.send(record);
        } finally {
            producer.close();
        }
    }
}
*/


class SetupMockSchemaRepository {
    static Path input_schema_path = Paths.get("src/main/avro/logistics_gen.avsc");
    static Path output_schema_path = Paths.get("src/main/avro/logistics_delivered.avsc");

    static void registerSchemas() {
        SchemaRegistryClient client = MockSchemaRegistry.getClientForScope("test_schema_registry");
        client.register("test_schema_registry", new AvroSchema(Files.readString(input_schema_path)));
        client.register("test_schema_registry", new AvroSchema(Files.readString(output_schema_path)));
    }
}







@ExtendWith(SystemStubsExtension.class)
class GenericFilterAppTest {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    @Test
    @DisplayName("Playing with GenericFilterApp")
    void testGenericFilterApp()
    {
        System.setProperty("KAFKA_SERVICE_URI",         "some sort of fake");
        System.setProperty("SSL_TRUSTSTORE_LOCATION",   "not used");
        System.setProperty("SSL_KEYSTORE_LOCATION",     "not used");
        System.setProperty("PASSWORD_FOR_STORE",        "not used");
        System.setProperty("SCHEMA_REGISTRY_URL",       "mock:test_schema_registry");
        System.setProperty("SCHEMA_REGISTRY_PASSWORD",  "not used");
        System.setProperty("SCHEMA_REGISTRY_USERNAME",  "not used");
        System.setProperty("INPUT_TOPIC",  "logistics_data_gen");        // the default
        System.setProperty("OUTPUT_TOPIC", "logistics_data_delivered");  // the default

        String[] arguments = new String[] {};

        Exception exception = assertThrows(RuntimeException.class, () ->{
            GenericFilterApp.main(arguments);
        });

    }

    @Test
    @DisplayName("Playing with the Config")
    void testBrokenConfigInMain()
    {
        System.setProperty("KAFKA_SERVICE_URI",         "ignore me");
        System.setProperty("SSL_TRUSTSTORE_LOCATION",   "ignore me");
        System.setProperty("SSL_KEYSTORE_LOCATION",     "ignore me");
        System.setProperty("PASSWORD_FOR_STORE",        "ignore me");
        System.setProperty("SCHEMA_REGISTRY_URL",       "ignore me");
        System.setProperty("SCHEMA_REGISTRY_PASSWORD",  "ignore me");
        System.setProperty("SCHEMA_REGISTRY_USERNAME", "");     // use the default
        System.setProperty("INPUT_TOPIC", "");                  // use the default
        System.setProperty("OUTPUT_TOPIC", "");                 // use the default

        String[] arguments = new String[] {};

        Exception exception = assertThrows(RuntimeException.class, () ->{
            GenericFilterApp.main(arguments);
        });


        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains("Failed to create new KafkaAdminClient"));
        //assertEquals(actualMessage, expectedMessage);
    }

	@Test
	@DisplayName("1 + 1 = 2 (good)")
	void additionGood() {
		assertEquals(2, 1 + 1);
	}

	@Test
	@DisplayName("1 + 2 = 2 (bad)")
	void additionBad() {
		assertEquals(2, 1 + 2);
	}

}
