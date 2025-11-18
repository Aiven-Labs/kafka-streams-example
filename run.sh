#!/bin/sh

# We're going to need the following environment variables as input
#
# - KAFKA_SERVICE_URI - the URI of the Kafka service we're using
# - CA_PEM_CONTENTS - the contents of the ca.pem file
# - SERVICE_CERT_CONTENTS - the contents of the service.cert file
# - SERVICE_KEY_CONTENTS - the contents of the service.key file

echo "SETTING UP certs DIRECTORY"
# Start with the certificate files
mkdir -p certs
# We're not sure if the environment variable will have newline or
# spaces separating "lines", so try to cope with either
echo "$CA_PEM_CONTENTS" | tr '\n' ' ' | tr ' ' '\n' > certs/ca.pem
echo "$SERVICE_CERT_CONTENTS" | tr '\n' ' ' | tr ' ' '\n' > certs/service.cert
echo "$SERVICE_KEY_CONTENTS" | tr '\n' ' ' | tr ' ' '\n' > certs/service.key

echo "ls -l certs"
ls -l certs

echo "SERVICE KEY FILE"
echo "vvvvvvvvvvvvvvvvvv"
cat certs/service.key
echo "^^^^^^^^^^^^^^^^^^"
echo "SERVICE CERT FILE"
echo "vvvvvvvvvvvvvvvvvv"
cat certs/service.cert
echo "^^^^^^^^^^^^^^^^^^"
echo "CA PEM FILE"
echo "vvvvvvvvvvvvvvvvvv"
cat certs/ca.pem
echo "^^^^^^^^^^^^^^^^^^"

# Generate a random password for our stores
export PASSWORD_FOR_STORE=`openssl rand -base64 10`

# Generate the key store
echo "GENERATING KEY STORE"
openssl pkcs12 -export            \
  -inkey $PWD/certs/service.key        \
  -in $PWD/certs/service.cert          \
  -out $PWD/certs/client.keystore.p12  \
  -password pass:$PASSWORD_FOR_STORE   \
  -name service_key

# and the trust store
echo "GENERATING TRUST STORE"
keytool -import                    \
  -file $PWD/certs/ca.pem               \
  -alias CA                        \
  -keypass $PASSWORD_FOR_STORE     \
  -storepass $PASSWORD_FOR_STORE   \
  -noprompt                        \
  -keystore $PWD/certs/client.truststore.jks

echo "RUNNING PROGRAM"
exec java \
    -cp '$JAVA_HOME/lib/*' \
    -DKAFKA_SERVICE_URI=$KAFKA_SERVICE_URI                     \
    -DSSL_TRUSTSTORE_LOCATION=$PWD/certs/client.truststore.jks \
    -DSSL_KEYSTORE_LOCATION=$PWD/certs/client.keystore.p12     \
    -DPASSWORD_FOR_STORE=$PASSWORD_FOR_STORE                   \
    -jar ./WordCountApp-uber.jar \
    com.example.WordCountApp "$@"
