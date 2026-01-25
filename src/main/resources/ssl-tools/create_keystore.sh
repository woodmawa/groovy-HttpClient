#!/bin/bash
#
# Create Keystore Script
# Generates PKCS12 keystores for development or production use
#

set -e

# Default values
TYPE="development"
OUTPUT="keystore.p12"
PASSWORD=""
HOSTNAME="localhost"
VALIDITY=365
ALIAS="dev-cert"
ORG="MyCompany"
ORG_UNIT="Development"
CITY="City"
STATE="State"
COUNTRY="US"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --type)
            TYPE="$2"
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
        --hostname)
            HOSTNAME="$2"
            shift 2
            ;;
        --validity)
            VALIDITY="$2"
            shift 2
            ;;
        --alias)
            ALIAS="$2"
            shift 2
            ;;
        --org)
            ORG="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --type TYPE          Certificate type: development|production (default: development)"
            echo "  --output FILE        Output keystore file (default: keystore.p12)"
            echo "  --password PASS      Keystore password (required)"
            echo "  --hostname HOST      Certificate hostname (default: localhost)"
            echo "  --validity DAYS      Certificate validity in days (default: 365)"
            echo "  --alias ALIAS        Certificate alias (default: dev-cert)"
            echo "  --org ORG            Organization name (default: MyCompany)"
            echo "  --help               Show this help message"
            echo ""
            echo "Examples:"
            echo "  # Development keystore"
            echo "  $0 --type development --output dev-keystore.p12 --password changeit --hostname localhost"
            echo ""
            echo "  # Production keystore (requires proper CA signing afterward)"
            echo "  $0 --type production --output prod-keystore.p12 --password \$PROD_PASSWORD --hostname api.example.com --validity 730"
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
if [ -z "$PASSWORD" ]; then
    echo "Error: --password is required"
    echo "Use --help for usage information"
    exit 1
fi

# Check if keytool is available
if ! command -v keytool &> /dev/null; then
    echo "Error: keytool not found. Please ensure Java is installed."
    exit 1
fi

# Set certificate type-specific values
case "$TYPE" in
    development)
        ORG_UNIT="Development"
        SAN="dns:$HOSTNAME,dns:*.localhost,ip:127.0.0.1"
        ;;
    production)
        ORG_UNIT="Production"
        SAN="dns:$HOSTNAME,dns:*.$HOSTNAME"
        ;;
    *)
        echo "Error: Unknown type '$TYPE'. Use 'development' or 'production'"
        exit 1
        ;;
esac

# Create keystore
echo "Creating $TYPE keystore..."
echo "  Output: $OUTPUT"
echo "  Hostname: $HOSTNAME"
echo "  Validity: $VALIDITY days"
echo "  Alias: $ALIAS"
echo ""

keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$OUTPUT" \
    -storepass "$PASSWORD" \
    -validity "$VALIDITY" \
    -dname "CN=$HOSTNAME, OU=$ORG_UNIT, O=$ORG, L=$CITY, ST=$STATE, C=$COUNTRY" \
    -ext "SAN=$SAN"

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Keystore created successfully: $OUTPUT"
    echo ""
    echo "Keystore details:"
    keytool -list -keystore "$OUTPUT" -storepass "$PASSWORD"
    echo ""

    if [ "$TYPE" == "development" ]; then
        echo "📝 Development Notes:"
        echo "  - This is a self-signed certificate suitable for development only"
        echo "  - To use in GroovyHttpClient, create a matching truststore:"
        echo "    keytool -exportcert -keystore $OUTPUT -storepass $PASSWORD -alias $ALIAS -file cert.crt"
        echo "    keytool -importcert -file cert.crt -keystore truststore.p12 -alias $ALIAS -noprompt"
        echo ""
    else
        echo "📝 Production Notes:"
        echo "  - This keystore contains a self-signed certificate"
        echo "  - For production, you need to:"
        echo "    1. Generate a CSR:"
        echo "       keytool -certreq -keystore $OUTPUT -alias $ALIAS -file request.csr"
        echo "    2. Submit CSR to your Certificate Authority"
        echo "    3. Import signed certificate:"
        echo "       keytool -importcert -keystore $OUTPUT -alias $ALIAS -file signed.crt"
        echo ""
    fi

    echo "⚠️  SECURITY WARNING:"
    echo "  - Store password securely (environment variable, secrets manager)"
    echo "  - Set restrictive file permissions: chmod 600 $OUTPUT"
    echo "  - Never commit keystores to version control"
    echo ""
else
    echo "✗ Error creating keystore"
    exit 1
fi