#!/bin/sh

# We're going to need the following environment variables as input
#
# - KAFKA_BOOTSTRAP_SERVERS - the URL of the Kafka service we're using
# - KAFKA_CA_CERT - the contents of the ca.pem file
# - KAFKA_ACCESS_CERT - the contents of the service.cert file
# - KAFKA_ACCESS_KEY - the contents of the service.key file
# - SCHEMA_REGISTRY_URL - the URL for the Karapace schema
#
# If you give values for INPUT_TOPIC and OUTPUT_TOPIC we'll use them,
# otherwise we've got defaults
export INPUT_TOPIC=${INPUT_TOPIC:-logistics_data_gen}
export OUTPUT_TOPIC=${OUTPUT_TOPIC:-logistics_data_delivered}

# We are (presumably) running as a containerised application
export RUNNING_AS_APP=True

echo "SET UP THE certs DIRECTORY"
# Start with the certificate files
mkdir -p certs

# We're not sure if the environment variable will have newlines seperating
# lines (which is what we assume the user will have given us) or if the
# newlins will have been replaced with spaces. So let's normalise the
# strings so we can cope with either. We take care not so "normalise"
# the spaces in the first and last lines :)
# Arguments to the function are
# * $1 the certificate string to normalise
# * $2 the phrase after BEGIN and END in the first and last lines
# * $3 is the file to write the string to
normalise_cert_to_file () {
  new_string=$(echo "$1" | sed "
      s/-----BEGIN $2-----/-----BEGIN-----/g
      s/-----END $2-----/-----END-----/g
      s/ /\n/g
      s/-----BEGIN-----/-----BEGIN $2-----/g
      s/-----END-----/-----END $2-----/g
  ")
  echo "$new_string" > $3
}

normalise_cert_to_file "$KAFKA_CA_CERT" "CERTIFICATE" certs/ca.pem
normalise_cert_to_file "$KAFKA_ACCESS_CERT" "CERTIFICATE" certs/service.cert
normalise_cert_to_file "$KAFKA_ACCESS_KEY" "PRIVATE KEY" certs/service.key

./serve.py
