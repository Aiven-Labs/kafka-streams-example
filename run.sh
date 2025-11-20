#!/bin/sh

# We're going to need the following environment variables as input
#
# - KAFKA_SERVICE_URI - the URI of the Kafka service we're using
# - CA_PEM_CONTENTS - the contents of the ca.pem file
# - SERVICE_CERT_CONTENTS - the contents of the service.cert file
# - SERVICE_KEY_CONTENTS - the contents of the service.key file

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

normalise_cert_to_file "$CA_PEM_CONTENTS" "CERTIFICATE" certs/ca.pem
normalise_cert_to_file "$SERVICE_CERT_CONTENTS" "CERTIFICATE" certs/service.cert
normalise_cert_to_file "$SERVICE_KEY_CONTENTS" "PRIVATE KEY" certs/service.key

# Generate a random password for our stores
export PASSWORD_FOR_STORE=`openssl rand -base64 10`

echo "GENERATE THE KEY STORE"
openssl pkcs12 -export            \
  -inkey $PWD/certs/service.key        \
  -in $PWD/certs/service.cert          \
  -out $PWD/certs/client.keystore.p12  \
  -password pass:$PASSWORD_FOR_STORE   \
  -name service_key

# and the trust store
echo "GENERATE THE TRUST STORE"
keytool -import                    \
  -file $PWD/certs/ca.pem               \
  -alias CA                        \
  -keypass $PASSWORD_FOR_STORE     \
  -storepass $PASSWORD_FOR_STORE   \
  -noprompt                        \
  -keystore $PWD/certs/client.truststore.jks

echo "RUN THE PROGRAM"
exec java \
    -cp '$JAVA_HOME/lib/*' \
    -DKAFKA_SERVICE_URI=$KAFKA_SERVICE_URI                     \
    -DSSL_TRUSTSTORE_LOCATION=$PWD/certs/client.truststore.jks \
    -DSSL_KEYSTORE_LOCATION=$PWD/certs/client.keystore.p12     \
    -DPASSWORD_FOR_STORE=$PASSWORD_FOR_STORE                   \
    -jar ./FilterApp-uber.jar \
    com.example.FilterApp "$@"
