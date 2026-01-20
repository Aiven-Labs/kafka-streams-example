package org.example;

import data.gen.avro.logistics;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@ExtendWith(SystemStubsExtension.class)
class GenericLogAppTests {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    static TopologyTestDriver testDriver;

    static TestInputTopic<String, logistics> inputTopic;

    static final String inputTopicName = "logistics_data_gen";

    static PrintStream systemErr;
    static ByteArrayOutputStream testErr;

    @BeforeAll
    static void setupBeforeAll() {
        Properties config = Config.getConfig("logistics_data_gen", "");
        Map<String, String> serdeConfig = Config.getSerdeConfig(config);

        SetupMockSchemaRepository.registerSchemas();

        Topology topology = GenericLogApp.buildTopology(config, serdeConfig);
        testDriver = new TopologyTestDriver(topology, config);

        SpecificAvroSerde<logistics> valueSerde = new SpecificAvroSerde<>();
        valueSerde.configure(serdeConfig, false);  // Just for the value, not the key

        inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), valueSerde.serializer());
    }

    @AfterAll
    static void tearDownAfterAll() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    @BeforeEach
    void setUpBeforeEach() {
        systemErr = System.err;
        testErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(testErr));
    }

    @AfterEach
    void tearDownAfterEach() {
        System.setErr(systemErr);
    }

    @Test
    @DisplayName("Test a message is logged")
    void testMessageIsLogged() {
        var now = System.currentTimeMillis();
        logistics inputValue = logistics.newBuilder()
                .setState("Delivered")
                .setTimeUtc(12345678)
                .setTrackingId("TRACK-ABC123")
                .setCarrier("UPS")
                .setMessage("Hidden somewhere outside your house")
                .setManifest(List.of("Cosy jumper", "Wooly hat"))
                .setNextHopLocation("None")
                .build();

        inputTopic.pipeInput("key", inputValue);

        // We expect fields in the log message to be reported in the order they are defined
        // in the schema - see the logistics_gen.avsc file
        String expectedLogEntry = """
                INFO org.example.GenericLogApp - LOOKING AT: Value='{"time_utc": 12345678, "tracking_id": "TRACK-ABC123", "message": "Hidden somewhere outside your house", "carrier": "UPS", "manifest": ["Cosy jumper", "Wooly hat"], "next_hop_location": "None", "state": "Delivered"}'
                """;

        String logMessages = testErr.toString();
        Assertions.assertTrue(logMessages.contains(expectedLogEntry));
    }
}