@echo off
REM
REM Convert PEM to PKCS12 Script (Windows)
REM Converts PEM format certificates to PKCS12 keystore
REM

setlocal enabledelayedexpansion

REM Default values
set INPUT=
set OUTPUT=keystore.p12
set PASSWORD=
set ALIAS=cert
set KEY_FILE=
set CA_FILE=

REM Parse arguments
:parse_args
if "%~1"=="" goto validate
if "%~1"=="--input" (
    set INPUT=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--output" (
    set OUTPUT=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--password" (
    set PASSWORD=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--alias" (
    set ALIAS=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--key" (
    set KEY_FILE=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--ca" (
    set CA_FILE=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--help" goto show_help
echo Unknown option: %~1
echo Use --help for usage information
exit /b 1

:show_help
echo Usage: %~n0 [OPTIONS]
echo.
echo Options:
echo   --input FILE         Input PEM certificate file (required)
echo   --output FILE        Output PKCS12 keystore file (default: keystore.p12)
echo   --password PASS      Keystore password (required)
echo   --alias ALIAS        Certificate alias (default: cert)
echo   --key FILE           Private key file (required for client certificates)
echo   --ca FILE            CA bundle file (optional)
echo   --help               Show this help message
echo.
echo Examples:
echo   # Convert CA certificate bundle to truststore
echo   %~n0 --input ca-bundle.pem --output truststore.p12 --password changeit
echo.
echo   # Convert client certificate with private key
echo   %~n0 --input client-cert.pem --key client-key.pem --output client-keystore.p12 --password secret
echo.
echo   # Convert with CA bundle
echo   %~n0 --input cert.pem --key key.pem --ca ca-bundle.pem --output keystore.p12 --password secret
exit /b 0

:validate
if "%INPUT%"=="" (
    echo Error: --input is required
    echo Use --help for usage information
    exit /b 1
)

if "%PASSWORD%"=="" (
    echo Error: --password is required
    echo Use --help for usage information
    exit /b 1
)

if not exist "%INPUT%" (
    echo Error: Input file not found: %INPUT%
    exit /b 1
)

where openssl >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo Error: openssl not found. Please install OpenSSL and add to PATH.
    echo Download from: https://slproweb.com/products/Win32OpenSSL.html
    exit /b 1
)

echo Converting PEM to PKCS12...
echo   Input: %INPUT%
echo   Output: %OUTPUT%
echo   Alias: %ALIAS%

REM Determine conversion type
if not "%KEY_FILE%"=="" (
    REM Client certificate with private key
    if not exist "%KEY_FILE%" (
        echo Error: Private key file not found: %KEY_FILE%
        exit /b 1
    )

    echo   Type: Client certificate with private key
    echo   Private key: %KEY_FILE%

    if not "%CA_FILE%"=="" (
        if not exist "%CA_FILE%" (
            echo Error: CA bundle file not found: %CA_FILE%
            exit /b 1
        )
        echo   CA bundle: %CA_FILE%

        REM Convert with CA bundle
        openssl pkcs12 -export ^
            -in "%INPUT%" ^
            -inkey "%KEY_FILE%" ^
            -certfile "%CA_FILE%" ^
            -out "%OUTPUT%" ^
            -name "%ALIAS%" ^
            -password "pass:%PASSWORD%"
    ) else (
        REM Convert without CA bundle
        openssl pkcs12 -export ^
            -in "%INPUT%" ^
            -inkey "%KEY_FILE%" ^
            -out "%OUTPUT%" ^
            -name "%ALIAS%" ^
            -password "pass:%PASSWORD%"
    )
) else (
    REM CA certificate bundle - use keytool for Windows
    echo   Type: CA certificate bundle (truststore)
    echo   NOTE: For multi-certificate bundles, use Linux script or import individually

    keytool -importcert ^
        -file "%INPUT%" ^
        -keystore "%OUTPUT%" ^
        -storetype PKCS12 ^
        -storepass "%PASSWORD%" ^
        -alias "%ALIAS%" ^
        -noprompt
)

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Conversion successful: %OUTPUT%
    echo.

    REM Show keystore contents
    echo PKCS12 keystore contents:
    keytool -list -keystore "%OUTPUT%" -storepass "%PASSWORD%"
    echo.

    if not "%KEY_FILE%"=="" (
        echo 📝 Client Certificate Notes:
        echo   - This keystore contains a private key for client authentication
        echo   - Use with SecurityConfig.keyManagers for mTLS
        echo.
    ) else (
        echo 📝 Truststore Notes:
        echo   - This truststore contains CA certificates for server verification
        echo   - Use with SecurityConfig.trustStore
        echo.
    )

    echo ⚠️  SECURITY WARNING:
    echo   - Store password securely (environment variable, secrets manager)
    echo   - Never commit keystores to version control
    echo.
) else (
    echo ✗ Error during conversion
    exit /b 1
)