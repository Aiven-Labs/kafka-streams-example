# Apache Kafka® Streams example applications for Avro

## What is here

This repository contains four example Apache Kafka® Streams applications, which
read and process [Apache Avro™](https://avro.apache.org/) messages. They
demonstrate use of Generic and Specific Avro Serdes, logging, copying and
filtering messages.

All default to reading from a topic called `logistics_data_gen`.

All message values are assume to be in Confluent style Avro, with the schema id
prepended to each value (See the Confluent 
[Wire format](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html#wire-format)
documentation for details of how this works). This means that the Serde can look
the schema up in a schema registry, and all the apps take advantage of that.

1. [GenericLogApp.java](app/src/main/java/org/example/GenericLogApp.java)

   This uses a GenericAvroSerde to read each message value, and logs it. It 
   does not write to an output topic.

2. [GenericCopyApp.java](app/src/main/java/org/example/GenericCopyApp.java)

   This uses a GenericAvroSerde to read each message value, and copies that 
   message to a different topic, using the same Serde.

The next two applications assume that the incoming messages match the schema in
[logistics_gen.avsc](app/src/main/avro/logistics_gen.avsc).

If you're using an Aiven for Apache Kafka service, then the Sample data
generator for "Logistics" will write appropriate messages to the 
`logistics_data_gen` topic, which this program will then filter, so that's a
good way of demonstrating that the program works.
 
3. [GenericFilterApp.java](app/src/main/java/org/example/GenericFilterApp.java)

   This uses a GenericAvroSerde to read each message value.

   * It ignores messages where the `state` is not `Delivered`.
   * It writes messages with values `timeUtc` (instead of `time_utc`),
     `trackingId` (instead of `tracking_id`), `carrier` and `manifest`.

   It writes those altered messages to the output topic using a 
   SpecificAvroSerde and the schema in 
   [logistics_delivered.avsc](app/src/main/avro/logistics_delivered.avsc).

4. [SpecificFilterApp.java](app/src/main/java/org/example/SpecificFilterApp.java)

   This is identical to `GenericFilterApp.java` except that it
   uses a SpecificAvroSerde and the schema in
   [logistics_gen.avsc](app/src/main/avro/logistics_gen.avsc).
   to read each message value.

All of the applications default to reading from the topic `logistics_data_gen`
and (except for the first) writing to the topic `logistics_data_delivered`. Both
topics must be on the same Kafka service.

> **Note** that if you run `GenericCopyApp` with output to the topic 
> `logistics_data_delivered`, and that topic already exists because one of
> the `xxxFilterApp` programs has written to it, you are likely
> to get an error, because the schema used for output by the `xxxFilterApp` 
> programs is not the same as that used by the copy app. It's better to
> specify a different output topic for the copy app.

The applications are designed to be run in a container - a `Dockerfile` and 
associated run scripts (`run.sh` and `setup_auth`) are provided.

By default the container and scripts will run the `GenericLogApp`, which 
should work for any Avro messages. You can choose a different app using the 
`APP_NAME` environment variable, either at the `docker build` command line, 
or by editing the `Dockerfile` itself.

The project uses Gradle and Groovy for configuration and building.

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

Do the same for the schema registry URL and password (the program
defaults to the standard schema Karapace username of `avnadmin`, so we don't
need to specify that).
```shell
export SCHEMA_REGISTRY_URL=<schema registry url>
```
```shell
export SCHEMA_REGISTRY_PASSWORD=<schema registry password>
```
(the Fish shell equivalents are left as an exercise for Fish shell users:).)

Set an environment variable to the content of each certificate file.

> Typically,
> 1. Download the certificate files for the Kafka service (`ca.pem`,
>    `service. cert` and `service.key`).
>    For an Aiven for Kafka service you can do this from the **Connection
>    information** in the service Overview.
> 2. Put the files into a directory called `certs` and use one of the
>    convenience shell scripts to read the content of
>    those files and set the environment variables:
>    ```shell
>    source prep.sh
>    ```
>    or for Fish
>    ```shell
>    source prep.fish
>    ```

1. To build a container image for the default `GenericLogApp`:

    ```shell
    docker build -t appimage .
    ```

2. To build a container image for a specific app (for instance, 
   `GenericFilterApp`):
    ```shell
    docker build --build-arg APP_NAME=GenericFilterApp -t appimage .
    ```

Run the container image:
```shell
docker run -d --name kafka-streams-container -p 3000:3000 \
        -e KAFKA_SERVICE_URI=$KAFKA_SERVICE_URI \
        -e CA_PEM_CONTENTS="$CA_PEM_CONTENTS" \
        -e SERVICE_CERT_CONTENTS="$SERVICE_CERT_CONTENTS" \
        -e SERVICE_KEY_CONTENTS="$SERVICE_KEY_CONTENTS" \
        -e SCHEMA_REGISTRY_URL=$SCHEMA_REGISTRY_URL \
        -e SCHEMA_REGISTRY_USERNAME=$SCHEMA_REGISTRY_USERNAME \ 
        -e SCHEMA_REGISTRY_PASSWORD=$SCHEMA_REGISTRY_PASSWORD \
        -e INPUT_TOPIC=$INPUT_TOPIC \
        -e OUTPUT_TOPIC=$OUTPUT_TOPIC \
        appimage
```

We don't actually use the port for anything at the moment.

Several of those environment variable arguments have defaults, so you can 
leave them off if you're happy with the default:
* `SCHEMA_REGISTRY_USERNAME` - default `avnadmin`
* `INPUT_TOPIC` - default `logistics_data_gen`
* `OUTPUT_TOPIC` - default `logistics_data_delivered`

## Command line arguments for the Java app

All variants of the Java app take the following arguments (of course 
`OUTPUT_TOPIC` is not used by the `Log` app). Common code to handle these is in
[Config.java](app/src/main/java/org/example/Config.java). The names chosen 
match the environment variables used by the container file and `run.sh`.

* `-DKAFKA_SERVICE_URI` - the URI for the Kafka service.
* `-DCA_PEM_CONTENTS` - the contents of the `ca.pem` file
* `-DSERVICE_CERT_CONTENTS` - the contents of the `service.cert` file
* `-DSERVICE_KEY_CONTENTS` - the contents of the `service.key` file
* `-DSCHEMA_REGISTRY_URL` - the URL for the schema registry.
* `-DSCHEMA_REGISTRY_USERNAME` - the user name for accessing the schema
  registry. This defaults to `avnadmin`, which is the default user name for
  Karapace.
* `-DSCHEMA_REGISTRY_PASSWORD` - the password for accessing the schema registry
* `-DINPUT_TOPIC` - the input topic name. This defaults to
  `logistics_data_gen`, which is the name of the topic written to by the
   Logistics data stream creator.
* `-DOUTPUT_TOPIC` - the output topic name. This defaults to
  `logistics_data_delivered`.

## The container file and how it works

This is a two stage container file.

The `APP_NAME` variable determines which app is being built and run. It 
defaults to `GenericLogApp` (which will work for any input topic).

The first stage builds a fat (uber) JAR for the program. This minimises the
size of the executable to be passed to the second stage.

It uses `jdeps` and `jlink` to work out the dependencies that are not in the
JAR file, and extract a minimum JRE from the larger JRE in provided by the
operating system used in that first stage.

The second stage then downloads `rocksdb` (used by Kafka Streams).

It then copies over the minimal JRE prepared in the first stage, and the fat
JAR itself, as well as the `run.sh` and `setup_auth.sh` files, and
finally runs the `run.sh` script.

## The `run.sh` and `setup_auth.sh` files

The `run.sh` file expects the following environment variables as input 
you'll recognise all but `APP_NAME` from the instructions on running the 
container and the Java app itself):

- `KAFKA_SERVICE_URI` - the URI of the Kafka service we're using
- `CA_PEM_CONTENTS` - the contents of the `ca.pem` file
- `SERVICE_CERT_CONTENTS` - the contents of the `service.cert` file
- `SERVICE_KEY_CONTENTS` - the contents of the `service.key` file
- `SCHEMA_REGISTRY_URL` - the URL for the schema registry
- `SCHEMA_REGISTRY_USERNAME` - the user name for accessing the schema
  registry. **This is optional** and if it is not given, a value of `avnadmin`
  will be assumed
- `SCHEMA_REGISTRY_PASSWORD` - the password for accessing the schema registry
- `INPUT_TOPIC` - the input topic name. **This is optional** and defaults to
  `logistics_data_gen`.
- `OUTPUT_TOPIC` - the output topic name. **This is optional** and defaults to
  `logistics_data_delivered`.
- `APP_NAME` - the name of the application to run. **This is optional** and 
  defaults to `GenericLogApp`.

It sources the `setup_auth.sh` script which makes sure that the
`CA_PEM_CONTENTS`, `SERVICE_CERT_CONTENTS` and `SERVICE_KEY_CONTENTS`
environment variables contain data that is correctly split into lines.

Finally the `run.sh` script  runs the fat Java JAR with the necessary
arguments.

## Building the program

We use a fat (uber) JAR in the container, so that all of the programs
non-standard dependencies (the ones not provided by the JRE) are frozen into the
final executable.

You can build that fat JAR file with
```shell
gradle ${APP_NAME}UberJar
```
where `$APP_NAME` is one of `GenericLogApp`, `GenericCopyApp`, 
`GenericFilterApp` or `SpecificFilterApp` -- for instance:
```shell
gradle SpecificFilterAppUberJar
```

(See `app/build.gradle` for the definition of the `UberJar` tasks.)

If you want to run the app using the provided `run.sh` script, then you'll
also need to copy the result to the top-level directory
```shell
cp app/build/libs/$APP_NAME-uber.jar .
```

### Running the unit tests

There are minimal unit tests for the four applications.

Run them with, for instance:
```shell
gradle clean cleanTest test
```

## Running `run.sh` locally

It's possible to run the `run.sh` script locally, and indeed this is useful
for testing. It's important to remember to

1. Copy the built app into the same directory as the `run.sh` script
2. Delete the `certs` directory each time, as the password used for the trust
   and key stores is different each time.
3. Set the required various environment variables first - these are also 
   documented at the top of the `run.sh` file.

For instance
```shell
rm -rf certs && ./run.sh
```
or
```shell
; rm -rf certs && APP_NAME=GenericFilterApp ./run.sh
```
or
```shell
; rm -rf certs && APP_NAME=GenericCopyApp OUTPUT_TOPIC=logistics_copy ./run.sh
```

## Visualising the messages

In the `reporting` directory there is a command line program
`report_messages.py` which reads messages from both the input and output topics
and shows them using a text UI.

If all the environment variables discussed before are set up, then you can 
run it with
```shell
reporting/report_messages.py
```

Get help on what it does with
```shell
reporting/report_messages.py -h
```

In that same directory there is an experimental wrapper (`serve.py`) which 
allows it to be run as a web app in a Docker container.

For instance:
```shell
cd reporting
```
```shell
docker build -t report_image .
```

```shell
docker run -d --name report-messages-container -p 3000:3000 \
        -e KAFKA_SERVICE_URI=$KAFKA_SERVICE_URI \
        -e CA_PEM_CONTENTS=$CA_PEM_CONTENTS \
        -e SERVICE_CERT_CONTENTS=$SERVICE_CERT_CONTENTS \
        -e SERVICE_KEY_CONTENTS=$SERVICE_KEY_CONTENTS \
        -e SCHEMA_REGISTRY_URL=$SCHEMA_REGISTRY_URL \
        report_image
```

It deliberately uses the same environment variables as are needed to run the 
actual application.

> **Note** It assumes that the `$SCHEMA_REGISTRY_URL` includes the username
> and password in the URL.

## End to end example using Aiven for Kafka

> **Note** For trying out this Kafka Streams app, a
> [free Aiven for Kafka service](https://aiven.io/free-kafka)
> will work just fine. The instructions below show how to use that, as well 
> as how to use a paid service if that's more suitable.

It's possible to do everything in this section using the Aiven [web
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
export KAFKA_SERVICE_NAME=<service name>
```
or for Fish shell
```shell
set -x KAFKA_SERVICE_NAME <service name>
```

Create the Aiven for Kafka service. We'll show how to create a free or paid 
service. There are notes about each command after the command.

1. For trying out this app, a
   [free Aiven for Kafka service](https://aiven.io/free-kafka)
   will work just fine. Create the service using the following command:
   ```shell
   avn service create $KAFKA_SERVICE_NAME          \
           --service-type kafka                    \
           --cloud do-ams                          \
           --plan free-0                           \
           -c schema_registry=true                 \
           -c kafka.auto_create_topics_enable=true
   ```

   > **Notes**
   > 1. The details of how the free cloud and plan are specified at the command 
   >    line may change. This is one case where it's actually simpler to do this
   >    in the Aiven web console, as there you just choose the free 
   >    Kafka tier and then what part of the world you want.
   > 2. `-c schema_registry=true` says we want to enable the Karapace schema
   >    registry. This is also free, and we need it to handle Avro messages.
   > 3. `-c kafka.auto_create_topics_enable=true` says we want producers to
   >    be able to create topics. You don't want this in production, but it's
   >    often a good idea in development, and it means the output topics will
   >    get created as we need them.

2. If you prefer (or if you're already using your free Aiven for Kafka service 
   for something else and don't want to add new topics to it), you can instead 
   create a paid service. For that, use a command like the following:
   ```shell
   avn service create $KAFKA_SERVICE_NAME          \
           --service-type kafka                    \
           --cloud aws-eu-west-1                   \
           --plan startup-4                        \
           --no-project-vpc                        \
           -c schema_registry=true                 \
           -c kafka.auto_create_topics_enable=true
   ```

   > **Notes**
   > 1. Choose a cloud and plan that match your needs. There's no need to go 
   >    for anything above the minimum plan (`startup-4` in this case).
   > 2. In the case of this cloud and region, I knew there was a VPC 
   >    (virtual private cloud) available to my organization, so I needed
   >    to tell the command I did not want to use it. It doesn't hurt to
   >    specify th
   > 3. The last two switches are the same as in the free example above.

While that's running, get the service URI for the new service
``` shell
export KAFKA_SERVICE_URI=$(avn service get $KAFKA_SERVICE_NAME --format '{service_uri}')
```
or for Fish shell
```shell
set -x KAFKA_SERVICE_URI (avn service get $KAFKA_SERVICE_NAME --format '{service_uri}')
```

Get the schema registry (Karapace) URL
```shell
export SCHEMA_REGISTRY_URL=$(avn service get $KAFKA_SERVICE_NAME --json | jq -r '.connection_info.schema_registry_uri')
```
or for Fish shell
```shell
set -x SCHEMA_REGISTRY_URL (avn service get $KAFKA_SERVICE_NAME --json | jq -r '.connection_info.schema_registry_uri')
```

Get the schema registry password
```shell
export SCHEMA_REGISTRY_PASSWORD=$(avn service get $KAFKA_SERVICE_NAME --json | jq -r '.users[0].password')
```
or for Fish shell
```shell
set -x SCHEMA_REGISTRY_PASSWORD (avn service get $KAFKA_SERVICE_NAME --json | jq -r '.users[0].password')
```

We assume the default username for the schema registry, so don't need to
look that up, but if you do need it then you can get it with
```shell
export SCHEMA_REGISTRY_USERNAME=$(avn service get $KAFKA_SERVICE_NAME --json | jq -r '.users[0].username')
```
or for Fish shell
```shell
set -x SCHEMA_REGISTRY_USERNAME (avn service get $KAFKA_SERVICE_NAME --json | jq -r '.users[0].username')
```

Wait for it to reach Running state
``` shell
avn service wait $KAFKA_SERVICE_NAME
```

Once the Kafka service is running, you can create the two topics if you're not using
the standard names, but
1. The Logistics sample data stream generator will create `logistics_data_gen`
   topic for you.
2. The `-c kafka.auto_create_topics_enable=true` specified when creating the
   service means the `logistics_data_delivered` topic will get created when the
   program tries to write to it.
```shell
avn service topic-create    \
    --partitions 1          \
    --replication 2         \
    $KAFKA_SERVICE_NAME logistics_data_gen
```
```shell
avn service topic-create    \
    --partitions 1          \
    --replication 2         \
    $KAFKA_SERVICE_NAME logistics_data_delivered
```

Download the certification files (it will create the directory if necessary)
``` shell
avn service user-creds-download $KAFKA_SERVICE_NAME --username avnadmin -d certs
```
``` shell
ls certs
```
should report
```
ca.pem  service.cert  service.key
```

Set the environment variables for the certificate file contents
```shell
source prep.sh
```
or for Fish shell
```shell
source prep.fish
```

And now you're ready to run the program, either via `rm -rf certs; ./run.sh`
or via Docker.
