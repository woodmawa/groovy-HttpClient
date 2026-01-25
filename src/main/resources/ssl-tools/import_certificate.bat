@echo off
REM
REM Import Certificate Script (Windows)
REM Imports CA certificates into PKCS12 truststore
REM

setlocal enabledelayedexpansion

REM Default values
set CERT=
set TRUSTSTORE=truststore.p12
set PASSWORD=
set ALIAS=

REM Parse arguments
:parse_args
if "%~1"=="" goto validate
if "%~1"=="--cert" (
    set CERT=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--truststore" (
    set TRUSTSTORE=%~2
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
if "%~1"=="--help" goto show_help
echo Unknown option: %~1
echo Use --help for usage information
exit /b 1

:show_help
echo Usage: %~n0 [OPTIONS]
echo.
echo Options:
echo   --cert FILE          Certificate file to import (.crt, .pem, .cer)
echo   --truststore FILE    Truststore file (default: truststore.p12)
echo   --password PASS      Truststore password (required)
echo   --alias ALIAS        Certificate alias (derived from filename if not provided)
echo   --help               Show this help message
echo.
echo Examples:
echo   # Import CA certificate
echo   %~n0 --cert company-ca.crt --truststore company-truststore.p12 --password changeit
echo.
echo   # Import with custom alias
echo   %~n0 --cert root-ca.pem --truststore truststore.p12 --password secret --alias company-root
exit /b 0

:validate
if "%CERT%"=="" (
    echo Error: --cert is required
    echo Use --help for usage information
    exit /b 1
)

if "%PASSWORD%"=="" (
    echo Error: --password is required
    echo Use --help for usage information
    exit /b 1
)

if not exist "%CERT%" (
    echo Error: Certificate file not found: %CERT%
    exit /b 1
)

REM Derive alias from filename if not provided
if "%ALIAS%"=="" (
    for %%F in ("%CERT%") do set ALIAS=%%~nF
    echo Using alias: !ALIAS!
)

where keytool >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo Error: keytool not found. Please ensure Java is installed and in PATH.
    exit /b 1
)

if exist "%TRUSTSTORE%" (
    echo Importing into existing truststore: %TRUSTSTORE%
) else (
    echo Creating new truststore: %TRUSTSTORE%
)

echo Importing certificate...
echo   Certificate: %CERT%
echo   Truststore: %TRUSTSTORE%
echo   Alias: %ALIAS%
echo.

keytool -importcert ^
    -file "%CERT%" ^
    -keystore "%TRUSTSTORE%" ^
    -storetype PKCS12 ^
    -storepass "%PASSWORD%" ^
    -alias "%ALIAS%" ^
    -noprompt

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Certificate imported successfully
    echo.
    echo Truststore contents:
    keytool -list -keystore "%TRUSTSTORE%" -storepass "%PASSWORD%"
    echo.

    echo 📝 Usage in GroovyHttpClient:
    echo.
    echo   def trustStore = java.security.KeyStore.getInstance("PKCS12")
    echo   new FileInputStream("%TRUSTSTORE%").withCloseable { fis -^>
    echo       trustStore.load(fis, "%PASSWORD%".toCharArray())
    echo   }
    echo.
    echo   def config = SecurityConfig.production("https://api.example.com")
    echo       .withOverrides([trustStore: trustStore])
    echo.

    echo ⚠️  SECURITY WARNING:
    echo   - Store password securely (environment variable, secrets manager)
    echo   - Never commit truststores to version control
    echo.
) else (
    echo ✗ Error importing certificate
    exit /b 1
)