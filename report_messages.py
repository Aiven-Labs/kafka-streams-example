#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["avro", "dotenv", "httpx", "kafka-python"]
# ///
#
# As to that `script` block - see
# https://packaging.python.org/en/latest/specifications/inline-script-metadata/#inline-script-metadata
# for more information.
# The uv program understands it, and so `uv run --script` will run this file
# and create a virtual environment for it on the fly.

"""Show messages from the output topic

Install `uv` and then run as `./report_messages.py`

Use `./report_messages.py -h` for help on how it is used.
"""

import argparse
import io
import json
import logging
import os
import pathlib
import struct
import sys

import avro
import avro.io
import avro.schema
import dotenv
import httpx

from pathlib import Path

from kafka import KafkaConsumer

DEFAULT_INPUT_TOPIC_NAME = 'logistics_data_gen'
DEFAULT_OUTPUT_TOPIC_NAME = 'logistics_data_delivered'

logging.basicConfig(level=logging.INFO)
# kafka-python itself likes to provide informative INFO log messages,
# but I'd rather not have them
logging.getLogger('kafka').setLevel(logging.WARNING)

# Command line default values
DEFAULT_CERTS_FOLDER = "certs"
KAFKA_SERVICE_URI = os.getenv("KAFKA_SERVICE_URI")
SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "logistics_data_delivered")
# Allow setting these defaults via a `.env` file as well
dotenv.load_dotenv()


def get_parsed_avro_schema(schema_as_str: str) -> avro.schema.RecordSchema:
    # Parsing the schema both validates it, and also puts it into a form that
    # can be used when envoding/decoding message data
    return avro.schema.parse(schema_as_str)


def lookup_avro_schema(schema_uri: str, schema_id: int) -> avro.schema.RecordSchema:
    """Look up the schema in Karapace"""
    logging.info(f"Looking up schema {schema_id}")
    r = httpx.get(f"{schema_uri}/schemas/ids/{schema_id}")
    r.raise_for_status()
    logging.info(f"Response is {r}")

    schema_as_str = r.text

    avro_schema = json.loads(schema_as_str)
    return get_parsed_avro_schema(avro_schema["schema"])


def unpack_avro_payload(
    message: bytes,
    schema_uri: str,
    cached_schema: dict[int : avro.schema.RecordSchema],
) -> dict:
    """Given an Avro message, look up the schema and unpack it.

    * `message` is the Avro message in Confluent style, prefixed with
      the schema id
    * `schema_uri` is where to find the Karapace server.
    * `cached_schema` is a very simple cache of known schemas, by
      their id. In actual fact, we expect to be using the schema
      that the application used to *create* messages, so our caller
      can pre-populate this.

    In a real production application, reading the messages would be
    in a separate application, and we'd need to be more sophisticated.
    """
    # The first 5 bytes should be a zero byte and then the schema id
    message_header = message[:5]
    zero_byte, schema_id = struct.unpack(">bI", message_header)
    if zero_byte != 0:
        raise ValueError(f"Avro message does not start with zero byte: {message}")

    if schema_id in cached_schema:
        parsed_schema = cached_schema[schema_id]
    else:
        parsed_schema = lookup_avro_schema(schema_uri, schema_id)
        # Remember it for later
        cached_schema[schema_id] = parsed_schema

    message_data = io.BytesIO(message[5:])

    reader = avro.io.DatumReader(parsed_schema)
    decoder = avro.io.BinaryDecoder(message_data)
    message_dict = reader.read(decoder)
    return message_dict


def report_messages(
    kafka_uri: str,
    certs_dir: pathlib.Path,
    schema_registry_url: str,
    topic_name: str,
):
    consumer = KafkaConsumer(
        bootstrap_servers=kafka_uri,
        security_protocol="SSL",
        ssl_cafile=certs_dir / "ca.pem",
        ssl_certfile=certs_dir / "service.cert",
        ssl_keyfile=certs_dir / "service.key",
        group_id='logistics_data',
        auto_offset_reset='earliest',
    )
    print(f'Subscribing to topic {topic_name}')
    consumer.subscribe([topic_name])

    print('About to read messages')
    cached_schema = {}
    try:
        for message in consumer:
            value = unpack_avro_payload(
                        message.value,
                        schema_registry_url,
                        cached_schema,
                    )
            print(f'Value {value}')
    finally:
        consumer.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '-k', '--kafka-uri', default=KAFKA_SERVICE_URI,
        help='the URI for the Kafka service, defaulting to $KAFKA_SERVICE_URI'
        ' if that is set',
    )
    parser.add_argument(
        '-d', '--certs-dir', default=DEFAULT_CERTS_FOLDER, type=pathlib.Path,
        help='directory containing the ca.pem, service.cert and service.key'
             f' files, default "{DEFAULT_CERTS_FOLDER}"',
    )
    parser.add_argument(
        '-s', '--schema-registry-url', default=SCHEMA_REGISTRY_URL,
        help='the URL for the schema registry service, defaulting to $SCHEMA_REGISTRY_URL'
             ' if that is set. It is assumed to have the username and password'
             'embedded in it',
    )
    parser.add_argument(
        '-o', '--output-topic', default=DEFAULT_OUTPUT_TOPIC_NAME,
        help=f'the output topic to read messages from, default "{DEFAULT_OUTPUT_TOPIC_NAME}"',
    )

    args = parser.parse_args()

    if args.kafka_uri is None:
        print('The URI for the Kafka service is required')
        print('Set KAFKA_SERVICE_URI or use the -k switch')
        logging.erro1Gr('The URI for the Kafka service is required')
        logging.error('Set KAFKA_SERVICE_URI or use the -k switch')
        return -1

    if args.schema_registry_url is None:
        print('The URL for the schema registry service is required')
        print('Set SCHEMA_REGISTRY_URL or use the -s switch')
        logging.error('The URL for the schema registry service is required')
        logging.error('Set SCHEMA_REGISTRY_URL or use the -s switch')
        return -1

    print('Reading messages')
    print(f'Kafka service URI {args.kafka_uri}')
    print(f'Certificates in {args.certs_dir}')
    print(f'Schema registry URL {args.schema_registry_url}')
    print(f'Reading from topic {args.output_topic}')

    report_messages(
        args.kafka_uri, args.certs_dir, args.schema_registry_url, args.output_topic,
    ),


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print()
