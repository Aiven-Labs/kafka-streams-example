#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["aiokafka", "avro", "dotenv", "httpx", "rich", "textual", "textual-serve"]
# ///

from textual_serve.server import Server

server = Server("uv run report_messages.py", host='0.0.0.0', port=3000)

server.serve()
