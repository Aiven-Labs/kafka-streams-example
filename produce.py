#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["faker", "dotenv", "kafka-python"]
# ///

# Why Python < 3.13? There seems to be a problem with Python's SSL context
# being stricter at 3.13 and later, which means it doesn't like the Aiven
# for Kafka certificates. I need to investigate this.
#
# As to that `script` block - see
# https://packaging.python.org/en/latest/specifications/inline-script-metadata/#inline-script-metadata
# for more information.
# The uv program understands it, and so `uv run --script` will run this file
# and create a virtual environment for it on the fly.

"""Generate fake logistics data

Install `uv` and then run as `./produce.py`

"""

"""Generate fake data."""

import argparse
import json
import logging
import os
import pathlib
import random
import sys

import dotenv

from faker import Faker
from kafka import KafkaProducer

DEFAULT_TOPIC_NAME = 'input-topic'

logging.basicConfig(level=logging.INFO)
# kafka-python itself likes to provide informative INFO log messages,
# but I'd rather not have them
logging.getLogger('kafka').setLevel(logging.WARNING)

# Command line default values
DEFAULT_CERTS_FOLDER = "certs"
KAFKA_SERVICE_URI = os.getenv("KAFKA_SERVICE_URI", "localhost:9093")
# Allow setting these defaults via a `.env` file as well
dotenv.load_dotenv()

def send_messages_to_kafka(
    kafka_uri: str,
    certs_dir: pathlib.Path,
    topic_name: str,
    num_messages: int,
):
    producer = KafkaProducer(
        bootstrap_servers=kafka_uri,
        security_protocol="SSL",
        ssl_cafile=certs_dir / "ca.pem",
        ssl_certfile=certs_dir / "service.cert",
        ssl_keyfile=certs_dir / "service.key",
    )

    fake = Faker()

    try:
        for count in range(num_messages):
            logging.info(f'Message {count+1} of {num_messages}')
            message = json.dumps({
                'name': fake.name(),
                'address': fake.address(),
                'tracking_id': fake.ssn(),
                'carrier': random.choice(["DHL", "Fedex", "USPS", "RoyalMail", "DeutschePost"]),
                'next_hop_location': random.choice(["DUB", "LON", "BER", "NYC", "PIT", "TOR", "MAD"]),
                'timestamp': fake.date_time_this_year().isoformat(timespec='minutes'),
                'state': random.choice(['Pending', 'Processing', 'OnRoute', 'Delivered']),
            } )
            producer.send(topic_name, message.encode('utf-8'))
    finally:
        producer.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '-k', '--kafka-uri', default=KAFKA_SERVICE_URI,
        help='the URI for the Kafka service, defaulting to $KAFKA_SERVICE_URI'
        ' if that is set',
    )
    parser.add_argument(
        '-d', '--certs-dir', default=DEFAULT_CERTS_FOLDER, type=pathlib.Path,
        help=f'directory containing the ca.pem, service.cert and service.key'
             ' files, default "{DEFAULT_CERTS_FOLDER}"',
    )
    parser.add_argument(
        '-t', '--topic', default=DEFAULT_TOPIC_NAME,
        help=f'the topic to produce messages to, default "{DEFAULT_TOPIC_NAME}"',
    )
    parser.add_argument(
        '--forever', action='store_true',
        help='generate fake "sessions" \'forever\''
        f' (actually equivalent to `--num-messages {sys.maxsize}`)'
        )
    parser.add_argument(
        '-n', '--num-messages', type=int, default=1,
        help='the number of messages to generate, defaulting to 1',
        )

    args = parser.parse_args()

    if args.kafka_uri is None:
        print('The URI for the Kafka service is required')
        print('Set KAFKA_SERVICE_URI or use the -k switch')
        logging.error('The URI for the Kafka service is required')
        logging.error('Set KAFKA_SERVICE_URI or use the -k switch')
        return -1

    if args.num_messages <= 0:
        print(f'The `--num-messages` argument must be 1 or more, not {args.num_messages}')
        logging.error(f'The `--num-messages` argument must be 1 or more, not {args.num_messages}')
        return -1

    if args.forever:
        args.num_sessions = sys.maxsize

    logging.info(f'Writing {args.num_messages} message{"" if args.num_messages==1 else "s"} to topic {args.topic}')

    send_messages_to_kafka(args.kafka_uri, args.certs_dir, args.topic, args.num_messages),


if __name__ == '__main__':
    main()
