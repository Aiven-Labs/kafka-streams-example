FROM ghcr.io/astral-sh/uv:python3.14-bookworm

# In production we'd want to pin to a particular version of uv
# See https://docs.astral.sh/uv/guides/integration/docker/#installing-uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

WORKDIR /app

# Copy our application and make it executable
COPY report_messages.py ./
RUN chmod +x ./report_messages.py

# Copy the textual-serve wrapper and ditto
COPY serve.py .
RUN chmod +x ./serve.py

# Copy the entrypoint script and make it executable
COPY run.sh ./
RUN chmod +x ./run.sh

# Set the custom entrypoint
CMD [ "./run.sh" ]
