#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["aiokafka", "avro", "dotenv", "httpx", "rich", "textual"]
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
import asyncio
import io
import json
import logging
import os
import pathlib
import ssl
import struct
import sys

from collections import deque
from datetime import datetime
from pathlib import Path

import aiokafka
import aiokafka.helpers
import avro
import avro.io
import avro.schema
import dotenv
import httpx

from rich.panel import Panel
from textual.app import App, ComposeResult
from textual.app import RenderResult
from textual.containers import Horizontal, Vertical
from textual.widgets import RichLog
from textual.widgets import Footer

DEFAULT_INPUT_TOPIC_NAME = 'logistics_data_gen'
DEFAULT_OUTPUT_TOPIC_NAME = 'logistics_data_delivered'

logging.basicConfig(level=logging.WARNING)
#logging.basicConfig(
#    format='%(asctime)s %(levelname)s %(funcName)s: %(message)s',
#    level=logging.INFO,
#)

# aiokafka itself likes to provide informative INFO log messages,
# but I'd rather not have them
logging.getLogger('aiokafka').setLevel(logging.WARNING)

# Try to stop log messages showing up in the panes
logging.propagate = False

# Command line default values
DEFAULT_CERTS_FOLDER = "certs"
KAFKA_SERVICE_URI = os.getenv("KAFKA_SERVICE_URI")
SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "logistics_data_delivered")
# Allow setting these defaults via a `.env` file as well
dotenv.load_dotenv()


class RichLogWidget(RichLog):
    """Provide the base functionality for our pane widget

    It's separated out so the subclass is easier to read

    Don't forget to re-implement background_task
    """

    # Maximum number of lines to keep for a widget display
    MAX_LINES = 40

    DEFAULT_CSS = """
    RichLogWidget {
        background: #f6fde3;
        height: 1fr;
        color: black;
        border: black;
    }
 
    .column {
        width: 1fr;
    }
    """

    def __init__(self, name: str) -> None:
        super().__init__(
            name=name,
            max_lines=self.MAX_LINES,
            markup=True,
        )

    def __str__(self):
        return self.name

    def add_line(self, text):
        """Add a line of text to our scrolling display"""
        self.write(text)

    async def background_task(self):
        while True:
            self.add_line('Implement a real background task')
            await asyncio.sleep(1)

    async def on_mount(self):
        self.border_title = self.name
        asyncio.create_task(self.background_task())


def get_parsed_avro_schema(schema_as_str: str) -> avro.schema.RecordSchema:
    # Parsing the schema both validates it, and also puts it into a form that
    # can be used when envoding/decoding message data
    return avro.schema.parse(schema_as_str)


def lookup_avro_schema(schema_uri: str, schema_id: int) -> avro.schema.RecordSchema:
    """Look up the schema in Karapace"""
    logging.debug(f"Looking up schema {schema_id}")
    r = httpx.get(f"{schema_uri}/schemas/ids/{schema_id}")
    r.raise_for_status()
    logging.debug(f"Response is {r}")

    schema_as_str = r.text

    avro_schema = json.loads(schema_as_str)
    return get_parsed_avro_schema(avro_schema["schema"])


async def unpack_avro_payload(
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


def timestamp_to_string(timestamp: int) -> str:
    """Return a readable string for a timestamp representing milliseconds since the epoch"""
    return datetime.fromtimestamp(timestamp / 1000.0).isoformat(sep=' ', timespec='minutes')


async def create_consumer(
        kafka_uri: str,
        ssl_context: ssl.SSLContext,
        name: str,
        topic_name: str,
) -> aiokafka.AIOKafkaConsumer:
    """Create a new Consumer, and wait for it to start.
    """
    logging.debug(f'Creating consumer {name} for {kafka_uri}')
    try:
        consumer = aiokafka.AIOKafkaConsumer(
            topic_name,
            bootstrap_servers=kafka_uri,
            security_protocol="SSL",
            ssl_context=ssl_context,
            group_id=None,
        )
    except Exception as e:
        logging.error(f'Error creating consumer {name}: {e.__class__.__name__} {e}')
        return
    logging.debug(f'Consumer {name} created')

    try:
        await consumer.start()
    except Exception as e:
        logging.error(f'Error starting consumer: {e.__class__.__name__} {e}')
        return
    logging.debug(f'Consumer {name} started')
    return consumer


class MessagePane(RichLogWidget):

    def __init__(
            self,
            name: str,
            kafka_uri: str,
            ssl_context: ssl.SSLContext,
            schema_registry_url: str,
            topic_name: str,
            is_input: bool,
    ) -> None:
        self.kafka_uri = kafka_uri
        self.ssl_context = ssl_context
        self.schema_registry_url = schema_registry_url
        self.topic_name = topic_name
        self.is_input = is_input
        super().__init__(name)

    async def background_task(self):
        consumer = await create_consumer(self.kafka_uri, self.ssl_context, str(self), self.topic_name)

        # Ignore any older messages - start with the most recent
        try:
            await consumer.seek_to_end()
        except Exception as e:
            self.add_line(f'Consumer seek-to-end Exception {e.__class__.__name__} {e}')
            return

        try:
            cached_schema = {}
            while True:
                async for message in consumer:
                    value = await unpack_avro_payload(message.value, self.schema_registry_url, cached_schema)
                    logging.debug(f'Topic {self.topic_name} message {value}')
                    if self.is_input:
                        self.report_input_message(value)
                    else:
                        self.report_output_message(value)
        except Exception as e:
            logging.error(f'Exception receiving message {e}')
            self.add_line(f'Exception receiving message {e}')
            await consumer.stop()
        finally:
            logging.debug(f'Consumer {self} stopping')
            self.add_line(f'Consumer {self} stopping')
            await consumer.stop()
            logging.debug(f'Consumer {self} stopped')

    def report_input_message(self, value: dict):
        if value["state"] == "Delivered":
            value["state"] = "DELIVERED"  # to make it stand out more and match the other pane
        self.add_line(f'[chartreuse]{timestamp_to_string(value["time_utc"])} {value["state"]}[/] {value["tracking_id"]} via {value["carrier"]} next hop {value["next_hop_location"]}')
        if value["message"]:
            self.add_line(f'    message "{value['message']}"')
        if value["manifest"] and value["manifest"][0]:
            self.add_line(f'    manifest {";".join(value["manifest"])}')

    def report_output_message(self, value: dict):
        self.add_line(f'{timestamp_to_string(value["timeUtc"])} DELIVERED {value["trackingId"]} via {value["carrier"]}')
        if value["manifest"] and value["manifest"][0]:
            self.add_line(f'    manifest {";".join(value["manifest"])}')


class MyGridApp(App):

    BINDINGS = [
        ("q", "quit()", "Quit"),
    ]

    def __init__(
            self,
            kafka_uri: str,
            ssl_context: ssl.SSLContext,
            schema_registry_url: str,
            input_topic_name: str,
            output_topic_name: str,
    ):
        self.kafka_uri = kafka_uri
        self.ssl_context = ssl_context
        self.schema_registry_url = schema_registry_url
        self.input_topic_name = input_topic_name
        self.output_topic_name = output_topic_name
        super().__init__()

    def compose(self) -> ComposeResult:
        with Vertical():
            yield MessagePane('Logistics Data (time_utc,state,tracking_id,carrier,next_hop_location,message,manifest)', self.kafka_uri, self.ssl_context, self.schema_registry_url,
                              self.input_topic_name, True)
            yield MessagePane('Logistics Data: Delivered (timeUtc,stage,trackingId,carrier,manifest)', self.kafka_uri, self.ssl_context,
                              self.schema_registry_url, self.output_topic_name, False)
            yield Footer()


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
        '-i', '--input-topic', default=DEFAULT_INPUT_TOPIC_NAME,
        help=f'the input topic to read messages from, default "{DEFAULT_INPUT_TOPIC_NAME}"',
    )
    parser.add_argument(
        '-o', '--output-topic', default=DEFAULT_OUTPUT_TOPIC_NAME,
        help=f'the output topic to read messages from, default "{DEFAULT_OUTPUT_TOPIC_NAME}"',
    )

    args = parser.parse_args()

    if args.kafka_uri is None:
        logging.error('The URI for the Kafka service is required')
        logging.error('Set KAFKA_SERVICE_URI or use the -k switch')
        return -1

    if args.schema_registry_url is None:
        logging.error('The URL for the schema registry service is required')
        logging.error('Set SCHEMA_REGISTRY_URL or use the -s switch')
        return -1

    logging.debug('Reading messages')
    logging.debug(f'Kafka service URI {args.kafka_uri}')
    logging.debug(f'Certificates in {args.certs_dir}')
    logging.debug(f'Schema registry URL {args.schema_registry_url}')
    logging.debug(f'Reading from topics {args.input_topic} and {args.output_topic}')

    try:
        ssl_context = aiokafka.helpers.create_ssl_context(
            cafile=args.certs_dir / "ca.pem",
            certfile=args.certs_dir / "service.cert",
            keyfile=args.certs_dir / "service.key",
        )
        # The helper function above calls ssl.create_default_context for us.
        # Python 3.13 made ssl.create_default_context apply stricter standards,
        # in particular VERIFY_X509_STRICT. This requires the Basic Constraints
        # of the CA cert to be marked critical. At the moment, the ca.pem
        # returned for an Aiven for Apache Kafka service does not do so.
        #
        # See https://discuss.python.org/t/ssl-changing-the-default-sslcontext-verify-flags/30230/12
        # for the historical context
        #
        # The workaround documented (but not recommended) at
        # https://docs.python.org/3/library/ssl.html#context-creation is
        ssl_context.verify_flags &= ~ssl.VERIFY_X509_STRICT

    except Exception as e:
        logging.error(f'Error loading SSL certificates from {args.certs_dir}')
        logging.error(f'{e.__class__.__name__} {e}')
        return -1

    app = MyGridApp(
        args.kafka_uri, ssl_context, args.schema_registry_url,
        args.input_topic, args.output_topic,
    )
    app.run()


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print()
