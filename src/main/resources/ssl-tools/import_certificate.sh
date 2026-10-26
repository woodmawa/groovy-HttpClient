#!/usr/bin/env bash
#
# Import Certificate Script (Linux/Mac)
# Imports a CA or server certificate into a PKCS12 truststore for use with GroovyHttpClient
# (SecurityConfig trustStore / builder.trustStore(KeyStore)).
#

set -e

CERT=""
TRUSTSTORE="truststore.p12"
PASSWORD=""
ALIAS=""

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --cert FILE          Certificate file to import (.crt, .pem, .cer) (required)"
    echo "  --truststore FILE    Truststore file (default: truststore.p12; created if missing)"
    echo "  --password PASS      Truststore password (required)"
    echo "  --alias ALIAS        Certificate alias (derived from the file name if not provided)"
    echo "  --help               Show this help message"
    echo ""
    echo "Example:"
    echo "  $0 --cert company-ca.crt --truststore company-truststore.p12 --password trustpass"
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --cert)       CERT="$2";       shift 2 ;;
        --truststore) TRUSTSTORE="$2"; shift 2 ;;
        --password)   PASSWORD="$2";   shift 2 ;;
        --alias)      ALIAS="$2";      shift 2 ;;
        --help)       usage; exit 0 ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

if [[ -z "$CERT" ]]; then
    echo "Error: --cert is required"
    echo "Use --help for usage information"
    exit 1
fi
if [[ ! -f "$CERT" ]]; then
    echo "Error: certificate file not found: $CERT"
    exit 1
fi
if [[ -z "$PASSWORD" ]]; then
    echo "Error: --password is required"
    echo "Use --help for usage information"
    exit 1
fi
if ! command -v keytool &> /dev/null; then
    echo "Error: keytool not found. Please ensure Java is installed and in PATH."
    exit 1
fi

if [[ -z "$ALIAS" ]]; then
    ALIAS="$(basename "$CERT")"
    ALIAS="${ALIAS%.*}"
fi

echo "Importing $CERT into $TRUSTSTORE as '$ALIAS'..."

keytool -importcert \
    -noprompt \
    -trustcacerts \
    -alias "$ALIAS" \
    -file "$CERT" \
    -keystore "$TRUSTSTORE" \
    -storetype PKCS12 \
    -storepass "$PASSWORD"

echo ""
echo "Truststore contents:"
keytool -list -keystore "$TRUSTSTORE" -storepass "$PASSWORD"

echo ""
echo "Done. Use it with GroovyHttpClient:"
echo "  def ks = KeyStore.getInstance('PKCS12')"
echo "  new File('$TRUSTSTORE').withInputStream { ks.load(it, password.toCharArray()) }"
echo "  GroovyHttpClient.builder(url).trustStore(ks).build()"
echo ""
echo "Notes:"
echo "  - Store the password securely (environment variable, secrets manager)"
echo "  - Never commit truststores holding private material to version control"
