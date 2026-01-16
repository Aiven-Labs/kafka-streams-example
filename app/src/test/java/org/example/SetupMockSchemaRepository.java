package org.example;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SetupMockSchemaRepository {

    static Path inputSchemaPath = Paths.get("src/main/avro/logistics_gen.avsc");
    static Path outputSchemaPath = Paths.get("src/main/avro/logistics_delivered.avsc");

    static void registerSchemas() {
        String inputSchema = "";
        String outputSchema = "";
        try {
            inputSchema = Files.readString(inputSchemaPath);
        } catch (IOException e) {
            System.out.println("Unable to read input schema " + e);
        }

        try {
            outputSchema = Files.readString(outputSchemaPath);
        } catch (IOException e) {
            System.out.println("Unable to read output schema " + e);
        }

        // AvroSchema implements ParsedSchema

        SchemaRegistryClient client = MockSchemaRegistry.getClientForScope("test_schema_registry");
        try {
            client.register("test_schema_registry", new AvroSchema(inputSchema));
        } catch (IOException | RestClientException e) {
            System.out.println("Unable to register input schema " + e);
        }
        try {
            client.register("test_schema_registry", new AvroSchema(outputSchema));
        } catch (IOException | RestClientException e) {
            System.out.println("Unable to register output schema " + e);
        }
    }
}
