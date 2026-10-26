# SSL/TLS Certificate and Keystore Management

Complete guide for creating, managing, and configuring SSL/TLS certificates and keystores for production use with GroovyHttpClient.

---

## Table of Contents

- [Overview](#overview)
- [Quick Start Scripts](#quick-start-scripts)
- [Certificate Types](#certificate-types)
- [Creating Keystores](#creating-keystores)
- [Importing Certificates](#importing-certificates)
- [SecurityConfig Integration](#securityconfig-integration)
- [Production Scenarios](#production-scenarios)
- [Corporate Proxies and TLS](#corporate-proxies-and-tls)
- [Troubleshooting](#troubleshooting)

---

## Overview

GroovyHttpClient supports custom SSL/TLS configurations through Java keystores and truststores. This is essential for:

- **Mutual TLS (mTLS)** — Client certificate authentication
- **Custom CA certificates** — Private/internal certificate authorities
- **Self-signed certificates** — Development and testing environments
- **Corporate proxy certificates** — Enterprise SSL inspection

> **Version note (2.0.0):** the `trustStore`, `trustManagers` and `keyManagers` settings used in this guide take effect from **2.0.0**. In 1.x they were silently ignored and the JVM default trust store was used, so private-CA and mutual-TLS configurations shown here did not work. From 2.0.0, `SecurityConfig` also refuses unknown setting names, so a misspelt key fails instead of being ignored. `GroovyHttpClient.builder(url)` offers the same as `.trustStore(ks)`, `.keyManagers(kms)` and `.sslContext(ctx)`. There is no `development()` profile: use `testing()` (verified TLS) with a trust store, or `trustAll()` for throwaway local debugging only.

> **Version note (2.1.0):** the same trust store, key managers and SSL context are used for everything the client connects to: the target API, an explicit HTTPS proxy tunnel, and an OAuth2 token endpoint. 2.1 adds explicit proxy settings; see [Corporate Proxies and TLS](#corporate-proxies-and-tls).

### Key Concepts

| Term | Description |
|------|-------------|
| **Keystore** | Contains private keys and certificates (for client authentication) |
| **Truststore** | Contains trusted CA certificates (for server verification) |
| **JKS** | Java KeyStore format (legacy, proprietary) |
| **PKCS12** | Standard format (.p12/.pfx) — **recommended** |
| **PEM** | Text format for certificates (.pem, .crt, .cer) |

---

## Quick Start Scripts

### Location

The scripts are in `src/main/resources/ssl-tools/` in the source repository:

```
src/main/resources/ssl-tools/
  create_keystore.sh          # Linux/Mac keystore creation
  create_keystore.bat         # Windows keystore creation
  import_certificate.sh       # Linux/Mac certificate import
  import_certificate.bat      # Windows certificate import
  convert_pem_to_p12.sh       # Linux/Mac PEM to PKCS12 conversion
  convert_pem_to_p12.bat      # Windows PEM to PKCS12 conversion
```

### Making Scripts Executable (Linux/Mac)

```bash
cd src/main/resources/ssl-tools
chmod +x *.sh
```

---

## Certificate Types

### 1. Development Certificates (Self-Signed)

**Use Case:** Local development, testing

```bash
# Generate self-signed certificate + keystore
./src/main/resources/ssl-tools/create_keystore.sh \
    --type development \
    --output dev-keystore.p12 \
    --password changeit \
    --hostname localhost \
    --validity 365
```

**Windows:**
```cmd
resources\ssl-tools\create_keystore.bat ^
    --type development ^
    --output dev-keystore.p12 ^
    --password changeit ^
    --hostname localhost ^
    --validity 365
```

### 2. Production Certificates (CA-Signed)

**Use Case:** Production environments with proper CA

You'll typically receive from your CA:
- `certificate.crt` — Your certificate
- `private-key.key` — Private key
- `ca-bundle.crt` — CA certificate chain

Convert to PKCS12:

```bash
openssl pkcs12 -export \
    -in certificate.crt \
    -inkey private-key.key \
    -certfile ca-bundle.crt \
    -out production-keystore.p12 \
    -name "production-cert" \
    -password pass:your-secure-password
```

### 3. Custom CA Certificates (Truststore)

**Use Case:** Internal CA, private PKI

```bash
# Import CA certificate into truststore
./src/main/resources/ssl-tools/import_certificate.sh \
    --cert company-ca.crt \
    --truststore company-truststore.p12 \
    --password truststore-password \
    --alias company-root-ca
```

**Windows:**
```cmd
resources\ssl-tools\import_certificate.bat ^
    --cert company-ca.crt ^
    --truststore company-truststore.p12 ^
    --password truststore-password ^
    --alias company-root-ca
```

---

## Creating Keystores

### Development Keystore (Self-Signed)

**Linux/Mac:**
```bash
#!/bin/bash
# Full development keystore creation

KEYSTORE="dev-keystore.p12"
PASSWORD="changeit"
ALIAS="dev-cert"
HOSTNAME="localhost"
VALIDITY=365

keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -validity $VALIDITY \
    -dname "CN=$HOSTNAME, OU=Development, O=MyCompany, L=City, ST=State, C=US" \
    -ext "SAN=dns:$HOSTNAME,dns:*.localhost,ip:127.0.0.1"

echo "✓ Development keystore created: $KEYSTORE"
echo "  Hostname: $HOSTNAME"
echo "  Password: $PASSWORD"
echo "  Validity: $VALIDITY days"
```

**Windows:**
```batch
@echo off
REM Full development keystore creation

set KEYSTORE=dev-keystore.p12
set PASSWORD=changeit
set ALIAS=dev-cert
set HOSTNAME=localhost
set VALIDITY=365

keytool -genkeypair ^
    -alias %ALIAS% ^
    -keyalg RSA ^
    -keysize 2048 ^
    -storetype PKCS12 ^
    -keystore %KEYSTORE% ^
    -storepass %PASSWORD% ^
    -validity %VALIDITY% ^
    -dname "CN=%HOSTNAME%, OU=Development, O=MyCompany, L=City, ST=State, C=US" ^
    -ext "SAN=dns:%HOSTNAME%,dns:*.localhost,ip:127.0.0.1"

echo ✓ Development keystore created: %KEYSTORE%
echo   Hostname: %HOSTNAME%
echo   Password: %PASSWORD%
echo   Validity: %VALIDITY% days
```

### Production Keystore from CA Certificates

```bash
#!/bin/bash
# Convert CA-issued certificates to PKCS12 keystore

CERT_FILE="production-cert.crt"
KEY_FILE="production-key.key"
CA_BUNDLE="ca-bundle.crt"
OUTPUT_KEYSTORE="production-keystore.p12"
KEYSTORE_PASSWORD="your-secure-production-password"
ALIAS="production-cert"

# Check if files exist
for file in "$CERT_FILE" "$KEY_FILE" "$CA_BUNDLE"; do
    if [ ! -f "$file" ]; then
        echo "✗ Error: $file not found"
        exit 1
    fi
done

# Create PKCS12 keystore
openssl pkcs12 -export \
    -in "$CERT_FILE" \
    -inkey "$KEY_FILE" \
    -certfile "$CA_BUNDLE" \
    -out "$OUTPUT_KEYSTORE" \
    -name "$ALIAS" \
    -password "pass:$KEYSTORE_PASSWORD"

if [ $? -eq 0 ]; then
    echo "✓ Production keystore created: $OUTPUT_KEYSTORE"
    echo "  Certificate: $CERT_FILE"
    echo "  Private Key: $KEY_FILE"
    echo "  CA Bundle: $CA_BUNDLE"
    echo ""
    echo "⚠️  IMPORTANT: Store password securely (environment variable or secrets manager)"
else
    echo "✗ Error creating keystore"
    exit 1
fi
```

---

## Importing Certificates

### Import CA Certificate into Truststore

**Linux/Mac:**
```bash
#!/bin/bash
# Import CA certificate into truststore

CA_CERT="company-ca.crt"
TRUSTSTORE="company-truststore.p12"
TRUSTSTORE_PASSWORD="truststore-password"
ALIAS="company-root-ca"

# Create truststore if it doesn't exist
if [ ! -f "$TRUSTSTORE" ]; then
    echo "Creating new truststore: $TRUSTSTORE"
fi

# Import certificate
keytool -importcert \
    -file "$CA_CERT" \
    -keystore "$TRUSTSTORE" \
    -storetype PKCS12 \
    -storepass "$TRUSTSTORE_PASSWORD" \
    -alias "$ALIAS" \
    -noprompt

if [ $? -eq 0 ]; then
    echo "✓ Certificate imported successfully"
    echo "  Certificate: $CA_CERT"
    echo "  Truststore: $TRUSTSTORE"
    echo "  Alias: $ALIAS"
    
    # List certificates
    echo ""
    echo "Truststore contents:"
    keytool -list -keystore "$TRUSTSTORE" -storepass "$TRUSTSTORE_PASSWORD"
else
    echo "✗ Error importing certificate"
    exit 1
fi
```

**Windows:**
```batch
@echo off
REM Import CA certificate into truststore

set CA_CERT=company-ca.crt
set TRUSTSTORE=company-truststore.p12
set TRUSTSTORE_PASSWORD=truststore-password
set ALIAS=company-root-ca

if not exist "%TRUSTSTORE%" (
    echo Creating new truststore: %TRUSTSTORE%
)

keytool -importcert ^
    -file "%CA_CERT%" ^
    -keystore "%TRUSTSTORE%" ^
    -storetype PKCS12 ^
    -storepass "%TRUSTSTORE_PASSWORD%" ^
    -alias "%ALIAS%" ^
    -noprompt

if %ERRORLEVEL% EQU 0 (
    echo ✓ Certificate imported successfully
    echo   Certificate: %CA_CERT%
    echo   Truststore: %TRUSTSTORE%
    echo   Alias: %ALIAS%
    
    echo.
    echo Truststore contents:
    keytool -list -keystore "%TRUSTSTORE%" -storepass "%TRUSTSTORE_PASSWORD%"
) else (
    echo ✗ Error importing certificate
    exit /b 1
)
```

### Import Multiple CA Certificates

```bash
#!/bin/bash
# Import multiple CA certificates into single truststore

TRUSTSTORE="multi-ca-truststore.p12"
TRUSTSTORE_PASSWORD="truststore-password"

# Array of CA certificates
CA_CERTS=(
    "root-ca.crt"
    "intermediate-ca.crt"
    "company-ca.crt"
)

# Import each certificate
for cert in "${CA_CERTS[@]}"; do
    if [ ! -f "$cert" ]; then
        echo "⚠️  Warning: $cert not found, skipping"
        continue
    fi
    
    # Extract alias from filename
    ALIAS=$(basename "$cert" .crt)
    
    echo "Importing $cert as $ALIAS..."
    keytool -importcert \
        -file "$cert" \
        -keystore "$TRUSTSTORE" \
        -storetype PKCS12 \
        -storepass "$TRUSTSTORE_PASSWORD" \
        -alias "$ALIAS" \
        -noprompt
    
    if [ $? -eq 0 ]; then
        echo "  ✓ Imported successfully"
    else
        echo "  ✗ Failed to import"
    fi
done

echo ""
echo "Truststore contents:"
keytool -list -keystore "$TRUSTSTORE" -storepass "$TRUSTSTORE_PASSWORD"
```

---

## SecurityConfig Integration

### 1. Custom Truststore (Verify Server Certificates)

```groovy
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient

// Load custom truststore
def trustStorePath = "/path/to/company-truststore.p12"
def trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD") ?: "truststore-password"

def trustStore = java.security.KeyStore.getInstance("PKCS12")
new FileInputStream(trustStorePath).withCloseable { fis ->
    trustStore.load(fis, trustStorePassword.toCharArray())
}

// Create SecurityConfig with custom truststore
def config = SecurityConfig.production("https://internal-api.company.com")
    .withOverrides([
        trustStore: trustStore,
        enforceHostnameVerification: true,
        allowedTlsProtocols: ["TLSv1.3", "TLSv1.2"]
    ])

def client = new GroovyHttpClient(config)
```

### 2. Custom Keystore (Client Certificate Authentication - mTLS)

```groovy
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient
import javax.net.ssl.*

// Load client keystore
def keyStorePath = "/path/to/client-keystore.p12"
def keyStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: "keystore-password"

def keyStore = java.security.KeyStore.getInstance("PKCS12")
new FileInputStream(keyStorePath).withCloseable { fis ->
    keyStore.load(fis, keyStorePassword.toCharArray())
}

// Create KeyManagerFactory
def kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
kmf.init(keyStore, keyStorePassword.toCharArray())

// Create SecurityConfig with client certificate
def config = SecurityConfig.production("https://mtls-api.company.com")
    .withOverrides([
        keyManagers: kmf.getKeyManagers(),
        enforceHostnameVerification: true,
        allowedTlsProtocols: ["TLSv1.3"]
    ])

def client = new GroovyHttpClient(config)

// All requests now include client certificate
def response = client.getSync("/secure-endpoint")
```

### 3. Custom Truststore + Keystore (Full mTLS)

```groovy
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient
import javax.net.ssl.*

// Load truststore (for verifying server)
def trustStorePath = "/path/to/company-truststore.p12"
def trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD")

def trustStore = java.security.KeyStore.getInstance("PKCS12")
new FileInputStream(trustStorePath).withCloseable { fis ->
    trustStore.load(fis, trustStorePassword.toCharArray())
}

def tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
tmf.init(trustStore)

// Load keystore (for client authentication)
def keyStorePath = "/path/to/client-keystore.p12"
def keyStorePassword = System.getenv("KEYSTORE_PASSWORD")

def keyStore = java.security.KeyStore.getInstance("PKCS12")
new FileInputStream(keyStorePath).withCloseable { fis ->
    keyStore.load(fis, keyStorePassword.toCharArray())
}

def kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
kmf.init(keyStore, keyStorePassword.toCharArray())

// Create SecurityConfig with both
def config = SecurityConfig.production("https://mtls-api.company.com")
    .withOverrides([
        trustManagers: tmf.getTrustManagers(),
        keyManagers: kmf.getKeyManagers(),
        enforceHostnameVerification: true,
        allowedTlsProtocols: ["TLSv1.3"]
    ])

def client = new GroovyHttpClient(config)
```

### 4. Development with Self-Signed Certificates

```groovy
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient

// Load development truststore with self-signed cert
def trustStorePath = "dev-truststore.p12"
def trustStorePassword = "changeit"

def trustStore = java.security.KeyStore.getInstance("PKCS12")
new FileInputStream(trustStorePath).withCloseable { fis ->
    trustStore.load(fis, trustStorePassword.toCharArray())
}

def config = SecurityConfig.testing("https://localhost:8443")
    .withOverrides([
        trustStore: trustStore,
        enforceHostnameVerification: false  // Development only!
    ])

def client = new GroovyHttpClient(config)
```

---

## Production Scenarios

### Scenario 1: Internal API with Corporate CA

**Problem:** Your company uses an internal certificate authority not trusted by default.

**Solution:**

1. **Obtain CA certificate** from your IT department:
```bash
# Usually provided as .crt or .pem file
company-root-ca.crt
```

2. **Create truststore:**
```bash
./src/main/resources/ssl-tools/import_certificate.sh \
    --cert company-root-ca.crt \
    --truststore company-truststore.p12 \
    --password $(cat /run/secrets/truststore-password) \
    --alias company-root-ca
```

3. **Configure GroovyHttpClient:**
```groovy
// Load truststore
def trustStore = loadTrustStore(
    "/etc/ssl/company-truststore.p12",
    System.getenv("TRUSTSTORE_PASSWORD")
)

def config = SecurityConfig.production("https://internal-api.company.com")
    .withOverrides([
        trustStore: trustStore,
        allowedHosts: ["*.company.com"],
        enforceHostnameVerification: true
    ])

def client = new GroovyHttpClient(config)
```

### Scenario 2: Mutual TLS (mTLS) with External Partner

**Problem:** External API requires client certificate authentication.

**Solution:**

1. **Generate CSR and obtain signed certificate:**
```bash
# Generate private key
openssl genrsa -out client-key.key 2048

# Generate CSR
openssl req -new \
    -key client-key.key \
    -out client-csr.csr \
    -subj "/CN=mycompany-client/O=MyCompany/C=US"

# Send CSR to partner, receive signed certificate
# Receive: client-cert.crt, partner-ca.crt
```

2. **Create keystore:**
```bash
# Convert to PKCS12
openssl pkcs12 -export \
    -in client-cert.crt \
    -inkey client-key.key \
    -certfile partner-ca.crt \
    -out client-keystore.p12 \
    -name "partner-mtls-cert" \
    -password "pass:$(cat /run/secrets/keystore-password)"
```

3. **Configure GroovyHttpClient:**
```groovy
def keyStore = loadKeyStore(
    "/etc/ssl/client-keystore.p12",
    System.getenv("KEYSTORE_PASSWORD")
)

def kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
kmf.init(keyStore, System.getenv("KEYSTORE_PASSWORD").toCharArray())

def config = SecurityConfig.production("https://partner-api.example.com")
    .withOverrides([
        keyManagers: kmf.getKeyManagers(),
        enforceHostnameVerification: true,
        allowedTlsProtocols: ["TLSv1.3"]
    ])

def client = new GroovyHttpClient(config)
```

### Scenario 3: Cloud Service with Custom CA Bundle

**Problem:** Connecting to cloud service with custom CA bundle.

**Solution:**

1. **Download CA bundle:**
```bash
# AWS example
curl -o aws-ca-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
```

2. **Convert PEM bundle to PKCS12:**
```bash
./src/main/resources/ssl-tools/convert_pem_to_p12.sh \
    --input aws-ca-bundle.pem \
    --output aws-truststore.p12 \
    --password $(cat /run/secrets/truststore-password)
```

3. **Configure GroovyHttpClient:**
```groovy
def trustStore = loadTrustStore(
    "/etc/ssl/aws-truststore.p12",
    System.getenv("TRUSTSTORE_PASSWORD")
)

def config = SecurityConfig.production("https://xxx.rds.amazonaws.com")
    .withOverrides([
        trustStore: trustStore,
        enforceHostnameVerification: true
    ])

def client = new GroovyHttpClient(config)
```

### Scenario 4: Development Environment (Self-Signed)

**Problem:** Need to test HTTPS locally without purchasing certificates.

**Solution:**

1. **Create self-signed certificate:**
```bash
./src/main/resources/ssl-tools/create_keystore.sh \
    --type development \
    --output dev-keystore.p12 \
    --password changeit \
    --hostname localhost \
    --validity 365
```

2. **Export certificate for import:**
```bash
keytool -exportcert \
    -keystore dev-keystore.p12 \
    -storepass changeit \
    -alias dev-cert \
    -file dev-cert.crt
```

3. **Create truststore:**
```bash
./src/main/resources/ssl-tools/import_certificate.sh \
    --cert dev-cert.crt \
    --truststore dev-truststore.p12 \
    --password changeit \
    --alias dev-cert
```

4. **Configure GroovyHttpClient:**
```groovy
def trustStore = loadTrustStore("dev-truststore.p12", "changeit")

def config = SecurityConfig.testing("https://localhost:8443")
    .withOverrides([
        trustStore: trustStore,
        enforceHostnameVerification: false  // Dev only!
    ])

def client = new GroovyHttpClient(config)
```

---

## Corporate Proxies and TLS

From 2.1 the client can be pointed at an explicit HTTP proxy (`proxy`, `proxyAuth`, `nonProxyHosts`, `noProxy` on the builder, or the matching `SecurityConfig` keys). Without them it uses the JVM's proxy settings, as in 2.0.

### Tunnelling proxies

For an `https://` target the client sends `CONNECT` to the proxy and runs TLS end to end with the target. Nothing in this guide changes: the target's certificate is verified against your trust store, and for mutual TLS your client certificate (`keyManagers`) is presented to the *target*, through the tunnel.

```groovy
def client = GroovyHttpClient.builder('https://partner-api.example.com')
        .proxy('proxy.corp.example', 3128)
        .trustStore(partnerCaStore)
        .keyManagers(kmf.keyManagers)      // mTLS with the target, through the tunnel
        .build()
```

### SSL-inspecting proxies

Some corporate proxies terminate TLS and re-sign every site with their own CA. The target's certificate then appears to be issued by the proxy's CA, and verification fails with `PKIX path building failed` unless that CA is trusted. Import the proxy's CA certificate (your IT team publishes it) into a truststore, together with any other CAs you need, and supply it:

```groovy
def client = GroovyHttpClient.builder('https://api.example.com')
        .proxy('proxy.corp.example', 3128)
        .trustStore(corporateTrustStore)   // contains the proxy's inspection CA
        .build()
```

Do not reach for `trustAll()` to get past an inspecting proxy: it disables verification for every site, not just the proxy.

### Proxy authentication

`proxyAuth(user, password)` sends Basic credentials to the proxy. For `https://` targets the JDK removes Basic credentials from `CONNECT` unless the JVM is started with:

```
-Djdk.http.auth.tunneling.disabledSchemes=""
```

This is a JVM-wide security setting, so the library does not set it. Without it the proxy answers 407 and the request fails with an `AuthenticationException` that names the property.

### What does not work through a proxy

- **SOCKS proxies**: the JDK `HttpClient` ignores them and connects directly, so a SOCKS proxy is refused when the client is built.
- **HTTP/3**: QUIC is UDP and cannot be tunnelled through an HTTP proxy. HTTP/3-only clients are refused; other HTTP/3 settings fall back to HTTP/2.

### OAuth2 token endpoints

An OAuth2 token request (`auth { oauth2 { ... } }`) goes through the same `HttpClient`: same trust store, key managers and proxy. If your identity provider uses a private CA, include it in the trust store you give the client. The token endpoint must be HTTPS (plain HTTP is accepted only for a loopback host or with `insecureAllowed`).

---

## Helper Methods

### Loading Keystores Safely

```groovy
class KeystoreLoader {
    
    static java.security.KeyStore loadKeyStore(String path, String password) {
        def keyStore = java.security.KeyStore.getInstance("PKCS12")
        
        def file = new File(path)
        if (!file.exists()) {
            throw new FileNotFoundException("Keystore not found: ${path}")
        }
        
        new FileInputStream(file).withCloseable { fis ->
            keyStore.load(fis, password.toCharArray())
        }
        
        return keyStore
    }
    
    static java.security.KeyStore loadTrustStore(String path, String password) {
        return loadKeyStore(path, password)  // Same process
    }
    
    static javax.net.ssl.KeyManager[] createKeyManagers(
        java.security.KeyStore keyStore,
        String password
    ) {
        def kmf = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(keyStore, password.toCharArray())
        return kmf.getKeyManagers()
    }
    
    static javax.net.ssl.TrustManager[] createTrustManagers(
        java.security.KeyStore trustStore
    ) {
        def tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)
        return tmf.getTrustManagers()
    }
}

// Usage
def keyStore = KeystoreLoader.loadKeyStore(
    "/etc/ssl/keystore.p12",
    System.getenv("KEYSTORE_PASSWORD")
)

def keyManagers = KeystoreLoader.createKeyManagers(
    keyStore,
    System.getenv("KEYSTORE_PASSWORD")
)

def config = SecurityConfig.production("https://api.example.com")
    .withOverrides([keyManagers: keyManagers])
```

### Environment-Based Configuration

```groovy
class SslConfigFactory {
    
    static SecurityConfig createConfig(String environment, String baseUrl) {
        switch (environment.toLowerCase()) {
            case 'development':
                return createDevelopmentConfig(baseUrl)
            case 'staging':
                return createStagingConfig(baseUrl)
            case 'production':
                return createProductionConfig(baseUrl)
            default:
                throw new IllegalArgumentException("Unknown environment: ${environment}")
        }
    }
    
    private static SecurityConfig createDevelopmentConfig(String baseUrl) {
        // Self-signed certificate support
        def trustStore = KeystoreLoader.loadTrustStore(
            "dev-truststore.p12",
            "changeit"
        )
        
        return SecurityConfig.testing(baseUrl)
            .withOverrides([
                trustStore: trustStore,
                enforceHostnameVerification: false
            ])
    }
    
    private static SecurityConfig createStagingConfig(String baseUrl) {
        // Company CA
        def trustStore = KeystoreLoader.loadTrustStore(
            System.getenv("STAGING_TRUSTSTORE_PATH"),
            System.getenv("STAGING_TRUSTSTORE_PASSWORD")
        )
        
        return SecurityConfig.production(baseUrl)
            .withOverrides([
                trustStore: trustStore,
                enforceHostnameVerification: true,
                allowedTlsProtocols: ["TLSv1.3", "TLSv1.2"]
            ])
    }
    
    private static SecurityConfig createProductionConfig(String baseUrl) {
        // Full mTLS
        def trustStore = KeystoreLoader.loadTrustStore(
            System.getenv("PROD_TRUSTSTORE_PATH"),
            System.getenv("PROD_TRUSTSTORE_PASSWORD")
        )
        
        def keyStore = KeystoreLoader.loadKeyStore(
            System.getenv("PROD_KEYSTORE_PATH"),
            System.getenv("PROD_KEYSTORE_PASSWORD")
        )
        
        def keyManagers = KeystoreLoader.createKeyManagers(
            keyStore,
            System.getenv("PROD_KEYSTORE_PASSWORD")
        )
        
        return SecurityConfig.production(baseUrl)
            .withOverrides([
                trustStore: trustStore,
                keyManagers: keyManagers,
                enforceHostnameVerification: true,
                allowedTlsProtocols: ["TLSv1.3"],
                allowedHosts: ["*.company.com", "api.partner.com"]
            ])
    }
}

// Usage
def env = System.getenv("APP_ENV") ?: "development"
def config = SslConfigFactory.createConfig(env, "https://api.example.com")
def client = new GroovyHttpClient(config)
```

---

## Troubleshooting

### Common Issues

#### 1. Certificate Verification Failed

**Error:**
```
javax.net.ssl.SSLHandshakeException: PKIX path building failed
```

**Solutions:**

```bash
# Check if certificate is in truststore
keytool -list -keystore truststore.p12 -storepass password

# Verify certificate chain
openssl s_client -connect api.example.com:443 -showcerts

# Import missing CA certificate
./src/main/resources/ssl-tools/import_certificate.sh \
    --cert missing-ca.crt \
    --truststore truststore.p12 \
    --password password \
    --alias missing-ca
```

#### 2. Hostname Verification Failed

**Error:**
```
javax.net.ssl.SSLPeerUnverifiedException: Hostname verification failed
```

**Solutions:**

```groovy
// Check certificate SAN (Subject Alternative Names)
// Ensure hostname matches certificate CN or SAN

// Temporary workaround (DEV ONLY):
def config = SecurityConfig.testing(baseUrl)
    .withOverrides([enforceHostnameVerification: false])

// Production solution: Fix certificate or use correct hostname
```

#### 3. Wrong Certificate Chain

**Error:**
```
sun.security.validator.ValidatorException: PKIX path validation failed
```

**Solutions:**

```bash
# View certificate chain
openssl s_client -connect api.example.com:443 -showcerts

# Ensure truststore has complete chain
# Import root CA
keytool -importcert -file root-ca.crt -keystore truststore.p12 -alias root-ca

# Import intermediate CA
keytool -importcert -file intermediate-ca.crt -keystore truststore.p12 -alias intermediate-ca
```

#### 4. Client Certificate Not Sent

**Error:**
```
HTTP 403 Forbidden or "Client certificate required"
```

**Solutions:**

```groovy
// Verify keystore contains certificate + private key
def keyStore = loadKeyStore(keystorePath, password)
def aliases = keyStore.aliases().toList()
println "Keystore aliases: ${aliases}"

// Verify certificate is valid
def cert = keyStore.getCertificate(alias)
println "Certificate: ${cert.getSubjectDN()}"

// Check expiration
println "Valid until: ${cert.getNotAfter()}"

// Verify KeyManagers are set
def kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
kmf.init(keyStore, password.toCharArray())

def config = SecurityConfig.production(baseUrl)
    .withOverrides([keyManagers: kmf.getKeyManagers()])
```

#### 5. Password Protected Private Key

**Error:**
```
java.io.IOException: keystore password was incorrect
```

**Solutions:**

```bash
# Ensure password is correct
keytool -list -keystore keystore.p12 -storepass correct-password

# If converting from PEM with encrypted key:
openssl pkcs12 -export \
    -in cert.crt \
    -inkey encrypted-key.key \
    -out keystore.p12 \
    -passout pass:keystore-password \
    -passin pass:key-password  # ← Password for private key
```

### Debugging SSL Issues

```groovy
// Enable SSL debugging
System.setProperty("javax.net.debug", "ssl:handshake")

// Create client
def client = new GroovyHttpClient(config)

// Make request - detailed SSL logs will appear
try {
    def response = client.getSync("/endpoint")
} catch (Exception e) {
    println "SSL Error: ${e.message}"
    e.printStackTrace()
}
```

### Certificate Information

```bash
# View keystore contents
keytool -list -v -keystore keystore.p12 -storepass password

# View specific certificate
keytool -list -v -keystore keystore.p12 -storepass password -alias cert-alias

# Export certificate for inspection
keytool -exportcert -keystore keystore.p12 -storepass password -alias cert-alias -file cert.crt

# View certificate details
openssl x509 -in cert.crt -text -noout

# Check certificate chain
openssl verify -CAfile ca-bundle.crt cert.crt
```

---

## Best Practices

### 1. Password Management

**❌ Don't:**
```groovy
// Hardcoded passwords
def password = "super-secret-password"
```

**✅ Do:**
```groovy
// Environment variables
def password = System.getenv("KEYSTORE_PASSWORD")
    ?: throw new IllegalStateException("KEYSTORE_PASSWORD not set")

// Docker secrets
def password = new File("/run/secrets/keystore-password").text.trim()

// Vault/Secrets manager
def password = secretsManager.getSecret("production/keystore-password")
```

### 2. File Permissions

```bash
# Restrict keystore access
chmod 600 keystore.p12
chown app:app keystore.p12

# Read-only for application
chmod 400 truststore.p12
```

### 3. Certificate Rotation

```groovy
class RotatingKeystore {
    private volatile KeyStore currentKeyStore
    private final String keystorePath
    private final String password
    
    RotatingKeystore(String path, String password) {
        this.keystorePath = path
        this.password = password
        reload()
        
        // Watch for changes
        startFileWatcher()
    }
    
    void reload() {
        currentKeyStore = KeystoreLoader.loadKeyStore(keystorePath, password)
        println "Keystore reloaded: ${keystorePath}"
    }
    
    KeyStore getKeyStore() {
        return currentKeyStore
    }
}

// Usage - automatically picks up new certificates
def rotatingKeystore = new RotatingKeystore(
    "/etc/ssl/keystore.p12",
    System.getenv("KEYSTORE_PASSWORD")
)
```

### 4. Certificate Validation

```bash
#!/bin/bash
# Validate certificate before deployment

CERT_FILE="production-cert.crt"
CA_FILE="ca-bundle.crt"

# Check expiration
EXPIRY=$(openssl x509 -in "$CERT_FILE" -noout -enddate | cut -d= -f2)
EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( ($EXPIRY_EPOCH - $NOW_EPOCH) / 86400 ))

if [ $DAYS_LEFT -lt 30 ]; then
    echo "⚠️  WARNING: Certificate expires in $DAYS_LEFT days"
    exit 1
fi

# Verify chain
openssl verify -CAfile "$CA_FILE" "$CERT_FILE"
if [ $? -ne 0 ]; then
    echo "✗ Certificate chain validation failed"
    exit 1
fi

echo "✓ Certificate valid for $DAYS_LEFT days"
```

### 5. Testing Certificates

```groovy
import spock.lang.Specification

class SslConfigSpec extends Specification {
    
    def "keystore should contain valid certificate"() {
        given:
        def keyStore = KeystoreLoader.loadKeyStore(
            "test-keystore.p12",
            "test-password"
        )
        
        when:
        def aliases = keyStore.aliases().toList()
        
        then:
        !aliases.isEmpty()
        
        and:
        def cert = keyStore.getCertificate(aliases[0])
        cert != null
        
        and: "certificate should not be expired"
        def now = new Date()
        cert.checkValidity(now)
    }
    
    def "should successfully connect with client certificate"() {
        given:
        def config = SslConfigFactory.createConfig("staging", testServerUrl)
        def client = new GroovyHttpClient(config)
        
        when:
        def response = client.getSync("/mtls-protected")
        
        then:
        response.statusCode == 200
    }
}
```

---

## Quick Reference

### Common Commands

```bash
# Create self-signed keystore
keytool -genkeypair -alias dev -keyalg RSA -keysize 2048 \
    -storetype PKCS12 -keystore dev-keystore.p12 -validity 365

# Import certificate
keytool -importcert -file ca.crt -keystore truststore.p12 \
    -alias ca-cert -noprompt

# List keystore contents
keytool -list -v -keystore keystore.p12

# Export certificate
keytool -exportcert -keystore keystore.p12 -alias cert \
    -file exported.crt

# Convert PEM to PKCS12
openssl pkcs12 -export -in cert.pem -inkey key.pem \
    -out keystore.p12

# View certificate
openssl x509 -in cert.crt -text -noout

# Test SSL connection
openssl s_client -connect api.example.com:443 -showcerts
```

### Environment Variables

```bash
# Keystore configuration
export KEYSTORE_PATH="/etc/ssl/keystore.p12"
export KEYSTORE_PASSWORD="secure-password"

# Truststore configuration  
export TRUSTSTORE_PATH="/etc/ssl/truststore.p12"
export TRUSTSTORE_PASSWORD="truststore-password"

# SSL debugging
export JAVAX_NET_DEBUG="ssl:handshake"
```

---

## Additional Resources

- [Java PKI Programmer's Guide](https://docs.oracle.com/en/java/javase/21/security/java-pki-programmers-guide.html)
- [Keytool Documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)
- [OpenSSL Documentation](https://www.openssl.org/docs/)
- [TLS 1.3 RFC](https://datatracker.ietf.org/doc/html/rfc8446)

---

**Created by Softwood Consulting Ltd**
