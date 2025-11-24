# Kafka Streams example

## What this does

This is an example program written in Java that uses Kafka Streams to filter
and process JSON messages.

It reads from `input-topic` and filters message to `output-topic`, on the same Kafka
service.

* It ignores input messages that are not JSON, or are not JSON objects (`{...}`)
* It ignores input messages where `state` is not set to `Delivered`
* In the messages it writes to `output-topic`, it writes the following fields:
  * `name`
  * `address`
  * `timestamp`
  * `tracking_id`, but it uses the name `trackingId`
* If any of those values are absent in the input message, they will be `null`
  in the output message.

It is designed to be run in a container - a `Dockerfile` and associated run
script (`run.sh`) are provided.

The project uses Gradle and Groovy for configuration.

## Command line arguments for the Java app

The Java app takes the following arguments:

* `-DKAFKA_SERVICE_URI` - the URI for the Kafka service
* `-DSSL_TRUSTSTORE_LOCATION` - the directory containing the `client.truststore.jks` file
* `-DSSL_KEYSTORE_LOCATION` - the directory containing the `client.keystore.p12` file
* `-DPASSWORD_FOR_STORE` - the password used for those stores (this assumes
  the same password is used for both)

## The container file and how it works

This is a two stage container file.

The first stage takes a fat (uber) JAR for the program, which is assumed to be
in the top level directory, and to be called `FilterApp-uber.jar`.

It uses `jdeps` and `jlink` to work out the depedencies that are not in the
JAR file, and extract a minimum JRE from the larger JRE in provided by the
operating system used in that first stage.

The second stage then downloads `openssl` (used in the `run.sh`) and `rocksdb`
(used by Kafka Streams - at least in it stateful KTable form - this might not
be needed for our stateless program).

It then copies over the minimal JRE prepared in the first stage, and the fat
JAR itself, as well as the `run.sh` file, which it runs.

## The `run.sh` file

The `run.sh` file assumes it has the following environment variabels as input:

- `KAFKA_SERVICE_URI` - the URI of the Kafka service we're using
- `CA_PEM_CONTENTS` - the contents of the `ca.pem` file
- `SERVICE_CERT_CONTENTS` - the contents of the `service.cert` file
- `SERVICE_KEY_CONTENTS` - the contents of the `service.key` file

It puts the contents of the environemnt variables into files of the
appropriate name in a directory called `certs`. It then uses `openssl` to
create a key store, and `keytool` to create a trust store. For both it uses a
password generated with `openssl`. Note that this means that the password does
not leave the container.

Finally it runs the fat Java JAR with the necessary arguments.

## Building the program

For simplicity in the container file, we build a fat (uber) JAR. This means
that all of non-standard dependencies are frozen into the final executable.

Build the JAR file with
```shell
gradle uberJar
```

(See `app/build.gradle` for the definition of the `uberJar` task.)

and copy the result to the top-level directory
```shell
cp app/build/libs/FilterApp-uber.jar .
```

For convenience there is already a `FilterApp-uber.jar` pre-built and
committed to this repository - this means you can run the program without
needing to build it.

## Running the container

Download the URI and the certificates for the Kafka service.

Set an environment variable for the Kafka service URI - something like
```shell
export KAFKA_SERVICE_URI=<service uri>
```
or in the Fish shell
```shell
set -x KAFKA_SERVICE_URI <service uri>
```

Set an environment variable to the content of each certificate file. You can
do this by hand, or for convenience there's a shell script:
```shell
source prep.sh
```
or for Fish
```shell
source prep.fish
```

Build the container image:
```shell
docker build -t appimage .
```

Run the container image:
```shell
docker run -d --name kafka-streams-container -p 3000:3000 \
        -e KAFKA_SERVICE_URI=$KAFKA_SERVICE_URI \
        -e CA_PEM_CONTENTS=$CA_PEM_CONTENTS \
        -e SERVICE_CERT_CONTENTS=$SERVICE_CERT_CONTENTS \
        -e SERVICE_KEY_CONTENTS=$SERVICE_KEY_CONTENTS \
        appimage
```

Note that we don't actually use the port for anything at the moment.

You can test by running the `produce.py` program, which generates random
messages in a compatible form:
```shell
./produce.py -n 5
```

(try `./produce -h` for help on what it can do.)

Check the messages are being sent with
```shell
kafkactl consume output-topic --from-beginning
```

## Running `run.sh` locally

It's possible to run the `run.sh` script locally, and indeed this is useful
for testing. It's important to remember to delete the `certs` directory each
time, as the password used for the trust and key stores is different each
time. Remember to set the various environment variables first.

So
```shell
rm -rf certs && ./run.sh
```

## End to end example using Aiven for Kafka

It's possible to do everything in this section using using the Aiven [web
console](https://console.aiven.io/), but for documentation purposes here I
shall use the `avn` command line tool.

Since `avn` is a Python tool, make sure you're in a virtual environment and
download it:

```shell
python -m venv venv
source venv/bin/activate    # If you're using fish, activate.fish
pip install aiven-client
```

Retrieve an Aiven session token (see [the
documentation](https://aiven.io/docs/platform/howto/create_authentication_token))
and login, using the email address you logged in to the console with, and pasting the token when prompted:
```shell
avn user login <your-email> --token
```

For convenience, set the project to your current project - this means you
don't have to specify it on every command:

```shell
avn project switch <project-name>
```

Set an environment variable for the service name - perhaps something like "kafka-streams-example"
```shell
export KAFKA_SERVICE_NAME <service name>
```

Create the service somewhere appropriate - change the actual location and so
on to suit
```shell
avn service create $KAFKA_SERVICE_NAME          \
        --service-type kafka                    \
        --cloud aws-eu-west-1                   \
        --plan startup-4                        \
        --no-project-vpc                        \
        -c schema_registry=true                 \
        -c kafka.auto_create_topics_enable=true
```

Get the service URI for the new service
``` shell
set -x KAFKA_SERVICE_URI (avn service get $KAFKA_SERVICE_NAME --format '{service_uri}')
```

Wait for it to reach Running state
``` shell
avn service wait $KAFKA_SERVICE_NAME
```

Once the Kafka service is running, create the two topics
```shell
avn service topic-create    \
    --partitions 1          \
    --replication 2         \
    $KAFKA_SERVICE_NAME input-topic
```
```shell
avn service topic-create    \
    --partitions 1          \
    --replication 2         \
    $KAFKA_SERVICE_NAME output-topic
```

Download the certification files (it will create the directory if necessary)
``` shell
avn service user-creds-download $KAFKA_SERVICE_NAME --username avnadmin -d certs
```
``` shell
ls certs
ca.pem  service.cert  service.key
```

Set the environment variables for the certificate file contents
```shell
source prep.sh
```

And now you're ready to run the program, either via `rm -rf certs; ./run.sh`
or via Docker.
