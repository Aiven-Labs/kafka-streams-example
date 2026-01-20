package org.example;

import static org.junit.jupiter.api.Assertions.*;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.StreamsException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import data.gen.avro.logistics;            // The class generated for our input messages
import data.gen.avro.logistics_delivered;  // The class generated for our output messages

@ExtendWith(SystemStubsExtension.class)
class GenericFilterAppTests {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    static TopologyTestDriver testDriver;

    @Nested
    @DisplayName("When input is logistics data")
    class InputIsLogisticsData {

        static TestInputTopic<String, logistics> inputTopic;
        static TestOutputTopic<String, logistics_delivered> outputTopic;

        static final String inputTopicName = "logistics_data_gen";
        static final String outputTopicName = "logistics_data_delivered";

        @BeforeAll
        static void setup() {
            SetupProperties.setProperties(inputTopicName, outputTopicName);

            Properties config = Config.getConfig("logistics_data_gen", "logistics_data_delivered");
            Map<String, String> serdeConfig = Config.getSerdeConfig(config);

            SetupMockSchemaRepository.registerSchemas();

            Topology topology = GenericFilterApp.buildTopology(config, serdeConfig);
            testDriver = new TopologyTestDriver(topology, config);

            SpecificAvroSerde<logistics> inputSerde = new SpecificAvroSerde<>();
            inputSerde.configure(serdeConfig, false);  // Just for the value, not the key

            SpecificAvroSerde<logistics_delivered> outputSerde = new SpecificAvroSerde<>();
            outputSerde.configure(serdeConfig, false); // just for the value

            inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), inputSerde.serializer());
            outputTopic = testDriver.createOutputTopic(outputTopicName, Serdes.String().deserializer(), outputSerde.deserializer());
        }

        @AfterAll
        static void tearDown() {
            if (testDriver != null) {
                testDriver.close();
            }
        }

        @Test
        @DisplayName("Test a Delivered message goes through")
        void testDeliveredMessagePropagates() {
            var now = System.currentTimeMillis();
            logistics inputValue = logistics.newBuilder()
                    .setState("Delivered")
                    .setTimeUtc(now)
                    .setTrackingId("TRACK-ABC123")
                    .setCarrier("UPS")
                    .setMessage("Hidden somewhere outside your house")
                    .setManifest(List.of("Cosy jumper", "Wooly hat"))
                    .setNextHopLocation("None")
                    .build();

            inputTopic.pipeInput("key", inputValue);

            KeyValue<String, logistics_delivered> outputRecord = outputTopic.readKeyValue();

            assertNotNull(outputRecord);
            assertEquals("key", outputRecord.key);
            assertEquals(now, outputRecord.value.getTimeUtc());
            assertEquals("UPS", outputRecord.value.getCarrier());
            assertEquals(List.of("Cosy jumper", "Wooly hat"), outputRecord.value.getManifest());
            assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
        }

        @Test
        @DisplayName("Test a Processing message does not go through")
        void testProcessingMessageDoesNotPropagate() {
            var now = System.currentTimeMillis();
            logistics inputValue = logistics.newBuilder()
                    .setState("Processing")
                    .setTimeUtc(now)
                    .setTrackingId("TRACK-ABC123")
                    .setCarrier("UPS")
                    .setMessage("Hidden somewhere outside your house")
                    .setManifest(List.of("Cosy jumper", "Wooly hat"))
                    .setNextHopLocation("None")
                    .build();

            inputTopic.pipeInput("key", inputValue);

            assertTrue(outputTopic.isEmpty(), "Processing message does not come through");
        }
    }

    @Nested
    @DisplayName("When input is partial data")
    class InputIsPartialData {

        static TestInputTopic<String, GenericRecord> inputTopic;
        static TestOutputTopic<String, logistics_delivered> outputTopic;

        static final String inputTopicName = "logistics_data_partial";  // not the same topic as the main tests
        static final String outputTopicName = "logistics_data_delivered";

        static AvroSchema inputSchema;

        static Path outputSchemaPath = Paths.get("src/main/avro/logistics_delivered.avsc");

        static void registerSchemas() {
            // We want to use a different Avro schema for the sent message,
            // as none of the fields in the normal logistics_gen schema are optional
            Schema.Parser parser = new Schema.Parser();
            inputSchema = new AvroSchema("""
                    {
                      "namespace": "data.gen.avro",
                      "name": "partialLogistics",
                      "type": "record",
                      "fields": [ { "name": "state", "type": { "type": "string" } } ]
                    }
                    """);

            String outputSchema = "";
            try {
                outputSchema = Files.readString(outputSchemaPath);
            } catch (IOException e) {
                System.out.println("Unable to read output schema " + e);
            }

            // AvroSchema implements ParsedSchema

            SchemaRegistryClient client = MockSchemaRegistry.getClientForScope("test_schema_registry");
            try {
                client.register("test_schema_registry", inputSchema);
            } catch (IOException | RestClientException e) {
                System.out.println("Unable to register input schema " + e);
            }
            try {
                client.register("test_schema_registry", new AvroSchema(outputSchema));
            } catch (IOException | RestClientException e) {
                System.out.println("Unable to register output schema " + e);
            }
        }

        @BeforeAll
        static void setup() {
            SetupProperties.setProperties(inputTopicName, outputTopicName);

            Properties config = Config.getConfig("logistics_data_gen", "logistics_data_delivered");
            Map<String, String> serdeConfig = Config.getSerdeConfig(config);

            registerSchemas();

            Topology topology = GenericFilterApp.buildTopology(config, serdeConfig);
            testDriver = new TopologyTestDriver(topology, config);

            GenericAvroSerde inputSerde = new GenericAvroSerde();
            inputSerde.configure(serdeConfig, false); // just for the value

            SpecificAvroSerde<logistics_delivered> outputSerde = new SpecificAvroSerde<>();
            outputSerde.configure(serdeConfig, false); // just for the value

            inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), inputSerde.serializer());
            outputTopic = testDriver.createOutputTopic(outputTopicName, Serdes.String().deserializer(), outputSerde.deserializer());
        }

        @AfterAll
        static void tearDown() {
            testDriver.close();
        }

        @Test
        @DisplayName("Test a Delivered message with partial content does not go through")
        void testPartialDeliveredMessageDoesNotPropagate() {
            GenericRecord inputValue = new GenericData.Record(inputSchema.rawSchema());
            inputValue.put("state", "Delivered");

            // We expect this to fail because the app tries to read fields out of the
            // message value that are not present and not defined in the input schema.
            // This should cause a StreamsException error, caused by an AvroRuntimeException
            // with a messsage something like:
            //    Not a valid schema field: time_utc

            Exception exception = assertThrows(StreamsException.class, () -> {
                inputTopic.pipeInput("key", inputValue);
            } );

            var cause = exception.getCause();
            assertEquals(AvroRuntimeException.class, cause.getClass());
            assertTrue(cause.getMessage().contains("Not a valid schema field"));
        }
    }
}