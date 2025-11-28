#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["aiokafka", "avro", "dotenv", "fastapi[standard]", "httpx", "ludic[full]", "uvicorn"]
# ///
#
# As to that `script` block - see
# https://packaging.python.org/en/latest/specifications/inline-script-metadata/#inline-script-metadata
# for more information.
# The uv program understands it, and so `uv run --script` will run this file
# and create a virtual environment for it on the fly.
"""A web app to show the input and output messages for our Kafka Streams app

The following must be provided, either as environment variables or in a `.env` file:

* KAFKA_SERVICE_URI - the URI for the Kafka bootstrap server, including username and password
* SCHEMA_REGISTRY_URI - the URI for the Karapace schema service, including username and password
* CERTS_DIR - the location of the certificates directory, default "certs"
* INPUT_TOPIC - the name of the input topic, default "logistics_data_gen"
* OUTPUT_TOPIC - the name of the output topic, default "logistics_data_delivered"
"""

import asyncio
import datetime
import json
import logging
import os
import ssl
import struct

from collections import deque
from pathlib import Path
from typing import Callable, Deque

from fastapi import FastAPI, Request
from ludic.attrs import Attrs
from ludic.html import div, table, tbody, td, th, thead, tr
from ludic.web import Endpoint, LudicApp
from ludic.catalog.pages import HtmlPage
from starlette.responses import HTMLResponse

import aiokafka
import aiokafka.helpers
import avro
import avro.io
import avro.schema
import dotenv
import httpx


logging.basicConfig(
    format='%(asctime)s %(levelname)s %(funcName)s: %(message)s',
    level=logging.INFO,
)

# aiokafka itself likes to provide informative INFO log messages,
# but I'd rather not have them
logging.getLogger('aiokafka').setLevel(logging.WARNING)

# Necessary environment variables
CERTS_DIR = Path(os.getenv("CERTS_DIR", "certs"))
KAFKA_SERVICE_URI = os.getenv("KAFKA_SERVICE_URI")      # including username and password
SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL")  # including username and password
INPUT_TOPIC = os.getenv("INPUT_TOPIC", "logistics_data_gen")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "logistics_data_delivered")

# Allow setting these defaults via a `.env` file as well
dotenv.load_dotenv()

MAX_INPUT_MESSAGES = 100
MAX_OUTPUT_MESSAGES = 100
# Shared in-memory data store for the latest N messages per topic
# Deque is used for efficient fixed-size message list (oldest messages pop off)
# Use a lock to protect access from the main thread and the consumer tasks
class AppState:
    def __init__(self):
        self.messages_input: Deque[dict] = deque(maxlen=MAX_INPUT_MESSAGES)
        self.messages_output: Deque[dict] = deque(maxlen=MAX_OUTPUT_MESSAGES)
        self.lock_input = asyncio.Lock()
        self.lock_output = asyncio.Lock()

app_state = AppState()

# -------------------------------------

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


def create_input_message_data(value: dict) -> dict:
    if value["manifest"] and value["manifest"][0]:
        manifest = ';'.join(value['manifest'])
    else:
        manifest = ''
    return {
        'time_utc': timestamp_to_string(value["time_utc"]),
        'state': value["state"],
        'tracking_id': value["tracking_id"],
        'carrier': value["carrier"],
        'next_hop_location': value["next_hop_location"],
        'message': value["message"],
        'manifest': manifest,
    }


def create_output_message_data(value: dict) -> dict:
    if value["manifest"] and value["manifest"][0]:
        manifest = ';'.join(value['manifest'])
    else:
        manifest = ''
    return {
        'timeUtc': timestamp_to_string(value["timeUtc"]),
        'trackingId': value["trackingId"],
        'carrier': value["carrier"],
        'manifest': manifest,
    }


async def consume_topic(
        kafka_uri: str,
        ssl_context: ssl.SSLContext,
        topic: str,
        schema_registry_url: str,
        message_data_fn: Callable[[bytes], dict],
        message_deque: Deque[dict],
        lock: asyncio.Lock,
):
    """
    Consumer task that reads messages from the `topic` and adds them to the appropriate `message_deque`
    """
    logging.info(f'Starting Kafka consumer for topic {topic}')
    consumer = aiokafka.AIOKafkaConsumer(
        topic,
        bootstrap_servers=kafka_uri,
        security_protocol="SSL",
        ssl_context=ssl_context,
        group_id=f"ludic-reporter-{topic.lower()}",
        auto_offset_reset='earliest',
    )
    await consumer.start()
    try:
        cached_schema = {}
        async for message in consumer:
            try:
                value = await unpack_avro_payload(message.value, schema_registry_url, cached_schema)
                message_data = create_message_data_fn(value)

                async with lock:
                    # Append new message to the shared deque
                    message_deque.appendleft(message_data) 
                    
            except Exception as e:
                logging.error(f"Error processing message from {topic}: {e}")

    finally:
        await consumer.stop()
        logging.info(f"Consumer for {topic} stopped.")


class MessageTable(Attrs):
    messages: Deque[dict]
    id: str


def KafkaTableInput(messages: Deque[dict], table_id: str):
    rows = []
    if msg["manifest"] and msg["manifest"][0]:
        manifest = ';'.join(msg['manifest'])
    else:
        manifest = ''
    for msg in messages:
        rows.append(tr(
            td(msg['time_utc']),
            td(msg['state']),
            td(msg['tracking_id']),
            td(msg['carrier']),
            td(msg['next_hop_location']),
            td(msg['message']),
            td(msg['manifest']),
        ))

    return table(
        thead(
            tr(
                th("time_utc"), th("state"), th("tracking_id"), th("carrier"), th("next_hop_location"), th("manifest"),
            )
        ),
        tbody(*rows),
        id=table_id,
        # Tailwind classes for styling (Ludic/HTMX often use utility CSS)
        _class="w-full text-sm text-left text-gray-500 dark:text-gray-400"
    )


def KafkaTableOutput(messages: Deque[dict], table_id: str):
    rows = []
    for msg in messages:
        rows.append(tr(
            td(msg['timeUtc']),
            td(msg['trackingIid']),
            td(msg['carrier']),
            td(msg['manifest']),
        ))
        
    return table(
        thead(
            tr(
                th("timeUtc"), th("trackingId"), th("carrier"), th("manifest"),
            )
        ),
        tbody(*rows),
        id=table_id,
        # Tailwind classes for styling (Ludic/HTMX often use utility CSS)
        _class="w-full text-sm text-left text-gray-500 dark:text-gray-400"
    )

# --- 3. HTMX Endpoints ---

class Reporter(Endpoint):
    path = "/"

    # Main page with the initial HTMX setup
    @classmethod
    async def get(cls, request: Request):
        # Define the body content
        content = div(
            div(h2("Kafka Stream Reporter"), _class="text-xl font-bold mb-4"),
            div(
                div(
                    h3(f"Topic A: {INPUT_TOPIC}", _class="text-lg font-semibold mb-2"),
                    div(
                        # The target div for HTMX updates
                        KafkaTableInput(app_state.messages_input, "table-input"),
                        # HTMX attributes for polling the update endpoint
                        hx_get=request.url_for("UpdateInput"),
                        hx_trigger="every 1s", # Polls every 1 second
                        hx_target="#table-input",
                        _class="overflow-y-scroll h-96 border p-2",
                        id="panel-input"
                    ),
                    _class="w-1/2 p-2"
                ),
                div(
                    h3(f"Topic B: {OUTPUT_TOPIC}", _class="text-lg font-semibold mb-2"),
                    div(
                        # The target div for HTMX updates
                        KafkaTableOutput(app_state.messages_output, "table-output"),
                        # HTMX attributes for polling the update endpoint
                        hx_get=request.url_for("UpdateOutput"),
                        hx_trigger="every 1s", # Polls every 1 second
                        hx_target="#table-output",
                        _class="overflow-y-scroll h-96 border p-2",
                        id="panel-output"
                    ),
                    _class="w-1/2 p-2"
                ),
                _class="flex space-x-4 w-full"
            )
        )

        return HtmlPage(
            title = "Kafka Reporter (Ludic + HTMX)",
            body = content,
            scripts = [
                {"src": "https://unpkg.com/htmx.org@1.9.10", "type": "text/javascript"} # Include HTMX library
            ],
            styles = [
                {"href": "https://cdn.tailwindcss.com", "rel": "stylesheet"} # Using a CDN for simplicity
            ]
        )

        return HTMLResponse(document.render())

# Endpoint to refresh Topic A panel content
class UpdateInput(Endpoint):
    path = "/update/input"
    @classmethod
    async def get(cls, request: Request):
        async with app_state.lock_input:
            html_fragment = KafkaTableInput(app_state.messages_input, "table-input").render()
            return HTMLResponse(html_fragment)

# Endpoint to refresh Topic B panel content
class UpdateOutput(Endpoint):
    path = "/update/output"
    @classmethod
    async def get(cls, request: Request):
        async with app_state.lock_output:
            html_fragment = KafkaTableOutput(app_state.messages_output, "table-output").render()
            return HTMLResponse(html_fragment)

# --- FastAPI/Starlette Application Setup ---

app = LudicApp(routes=[Reporter, UpdateInput, UpdateOutput])

# Start the Kafka consumers as background tasks when the application starts
@app.on_event("startup")
async def startup_event():

    try:
        ssl_context = aiokafka.helpers.create_ssl_context(
            cafile=CERTS_DIR / "ca.pem",
            certfile=CERTS_DIR / "service.cert",
            keyfile=CERTS_DIR / "service.key",
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
        logging.error(f'Error loading SSL certificates from {CERTS_DIR}')
        logging.error(f'{e.__class__.__name__} {e}')
        raise

    # Start the two non-blocking consumer tasks
    app.state.consumer_task_input = asyncio.create_task(
        consume_topic(
            KAFKA_SERVICE_URI,
            ssl_context,
            INPUT_TOPIC,
            SCHEMA_REGISTRY_URL,
            create_input_message_data,
            app_state.messages_input,
            app_state.lock_input,
        )
    )
    app.state.consumer_task_output = asyncio.create_task(
        consume_topic(
            KAFKA_SERVICE_URI,
            ssl_context,
            OUTPUT_TOPIC,
            SCHEMA_REGISTRY_URL,
            create_output_message_data,
            app_state.messages_output,
            app_state.lock_output,
        )
    )

@app.on_event("shutdown")
async def shutdown_event():
    # Ensure tasks are properly cancelled when the application shuts down
    app.state.consumer_task_input.cancel()
    app.state.consumer_task_output.cancel()
    # Wait for tasks to finish (including consumer.stop())
    await asyncio.gather(app.state.consumer_task_input, app.state.consumer_task_output, return_exceptions=True)

# To run: uvicorn app:app --reload
if __name__ == "__main__":
  import uvicorn
  uvicorn.run("show_messages_app:app",reload=True)
