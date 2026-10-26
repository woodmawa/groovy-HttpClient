#!/bin/bash
#
# Convert PEM to PKCS12 Script
# Converts PEM format certificates to PKCS12 keystore
#

set -e

# Default values
INPUT=""
OUTPUT="keystore.p12"
PASSWORD=""
ALIAS="cert"
KEY_FILE=""
CA_FILE=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --input)
            INPUT="$2"
            shift 2
            ;;
        --output)
            OUTPUT="$2"
            shift 2
            ;;
        --password)
            PASSWORD="$2"
            shift 2
            ;;
        --alias)
            ALIAS="$2"
            shift 2
            ;;
        --key)
            KEY_FILE="$2"
            shift 2
            ;;
        --ca)
            CA_FILE="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --input FILE         Input PEM certificate file (required)"
            echo "  --output FILE        Output PKCS12 keystore file (default: keystore.p12)"
            echo "  --password PASS      Keystore password (required)"
            echo "  --alias ALIAS        Certificate alias (default: cert)"
            echo "  --key FILE           Private key file (required for client certificates)"
            echo "  --ca FILE            CA bundle file (optional)"
            echo "  --help               Show this help message"
            echo ""
            echo "Examples:"
            echo "  # Convert CA certificate bundle to truststore"
            echo "  $0 --input ca-bundle.pem --output truststore.p12 --password changeit"
            echo ""
            echo "  # Convert client certificate with private key"
            echo "  $0 --input client-cert.pem --key client-key.pem --output client-keystore.p12 --password secret"
            echo ""
            echo "  # Convert with CA bundle"
            echo "  $0 --input cert.pem --key key.pem --ca ca-bundle.pem --output keystore.p12 --password secret"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Validate required parameters
if [ -z "$INPUT" ]; then
    echo "Error: --input is required"
    echo "Use --help for usage information"
    exit 1
fi

if [ -z "$PASSWORD" ]; then
    echo "Error: --password is required"
    echo "Use --help for usage information"
    exit 1
fi

# Check if input file exists
if [ ! -f "$INPUT" ]; then
    echo "Error: Input file not found: $INPUT"
    exit 1
fi

# Check if openssl is available
if ! command -v openssl &> /dev/null; then
    echo "Error: openssl not found. Please install OpenSSL."
    exit 1
fi

echo "Converting PEM to PKCS12..."
echo "  Input: $INPUT"
echo "  Output: $OUTPUT"
echo "  Alias: $ALIAS"

# Determine conversion type
if [ -n "$KEY_FILE" ]; then
    # Client certificate with private key
    if [ ! -f "$KEY_FILE" ]; then
        echo "Error: Private key file not found: $KEY_FILE"
        exit 1
    fi

    echo "  Type: Client certificate with private key"
    echo "  Private key: $KEY_FILE"

    if [ -n "$CA_FILE" ]; then
        if [ ! -f "$CA_FILE" ]; then
            echo "Error: CA bundle file not found: $CA_FILE"
            exit 1
        fi
        echo "  CA bundle: $CA_FILE"

        # Convert with CA bundle
        openssl pkcs12 -export \
            -in "$INPUT" \
            -inkey "$KEY_FILE" \
            -certfile "$CA_FILE" \
            -out "$OUTPUT" \
            -name "$ALIAS" \
            -password "pass:$PASSWORD"
    else
        # Convert without CA bundle
        openssl pkcs12 -export \
            -in "$INPUT" \
            -inkey "$KEY_FILE" \
            -out "$OUTPUT" \
            -name "$ALIAS" \
            -password "pass:$PASSWORD"
    fi
else
    # CA certificate bundle (truststore)
    echo "  Type: CA certificate bundle (truststore)"

    # Split PEM bundle and import each certificate
    TEMP_DIR=$(mktemp -d)
    trap "rm -rf $TEMP_DIR" EXIT

    # Split bundle into individual certificates
    csplit -s -z -f "$TEMP_DIR/cert-" "$INPUT" '/-----BEGIN CERTIFICATE-----/' '{*}'

    # Import each certificate
    CERT_COUNT=0
    for cert_file in "$TEMP_DIR"/cert-*; do
        if [ -s "$cert_file" ]; then
            CERT_COUNT=$((CERT_COUNT + 1))
            CERT_ALIAS="${ALIAS}-${CERT_COUNT}"

            echo "  Importing certificate $CERT_COUNT as $CERT_ALIAS..."

            keytool -importcert \
                -file "$cert_file" \
                -keystore "$OUTPUT" \
                -storetype PKCS12 \
                -storepass "$PASSWORD" \
                -alias "$CERT_ALIAS" \
                -noprompt
        fi
    done

    echo "  Imported $CERT_COUNT certificate(s)"
fi

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Conversion successful: $OUTPUT"
    echo ""

    # Show keystore contents
    echo "PKCS12 keystore contents:"
    keytool -list -keystore "$OUTPUT" -storepass "$PASSWORD"
    echo ""

    if [ -n "$KEY_FILE" ]; then
        echo "📝 Client Certificate Notes:"
        echo "  - This keystore contains a private key and can be used for client authentication"
        echo "  - Use with SecurityConfig.keyManagers for mTLS"
        echo ""
    else
        echo "📝 Truststore Notes:"
        echo "  - This truststore contains CA certificates for server verification"
        echo "  - Use with SecurityConfig.trustStore"
        echo ""
    fi

    echo "⚠️  SECURITY WARNING:"
    echo "  - Store password securely (environment variable, secrets manager)"
    echo "  - Set restrictive file permissions: chmod 600 $OUTPUT"
    echo "  - Never commit keystores to version control"
    echo ""
else
    echo "✗ Error during conversion"
    exit 1
fi