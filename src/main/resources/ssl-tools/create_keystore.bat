@echo off
REM
REM Create Keystore Script (Windows)
REM Generates PKCS12 keystores for development or production use
REM

setlocal enabledelayedexpansion

REM Default values
set TYPE=development
set OUTPUT=keystore.p12
set PASSWORD=
set HOSTNAME=localhost
set VALIDITY=365
set ALIAS=dev-cert
set ORG=MyCompany
set ORG_UNIT=Development
set CITY=City
set STATE=State
set COUNTRY=US

REM Parse arguments
:parse_args
if "%~1"=="" goto validate
if "%~1"=="--type" (
    set TYPE=%~2
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
if "%~1"=="--hostname" (
    set HOSTNAME=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--validity" (
    set VALIDITY=%~2
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
if "%~1"=="--org" (
    set ORG=%~2
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
echo   --type TYPE          Certificate type: development^|production (default: development)
echo   --output FILE        Output keystore file (default: keystore.p12)
echo   --password PASS      Keystore password (required)
echo   --hostname HOST      Certificate hostname (default: localhost)
echo   --validity DAYS      Certificate validity in days (default: 365)
echo   --alias ALIAS        Certificate alias (default: dev-cert)
echo   --org ORG            Organization name (default: MyCompany)
echo   --help               Show this help message
echo.
echo Examples:
echo   # Development keystore
echo   %~n0 --type development --output dev-keystore.p12 --password changeit --hostname localhost
echo.
echo   # Production keystore
echo   %~n0 --type production --output prod-keystore.p12 --password %%PROD_PASSWORD%% --hostname api.example.com
exit /b 0

:validate
if "%PASSWORD%"=="" (
    echo Error: --password is required
    echo Use --help for usage information
    exit /b 1
)

where keytool >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo Error: keytool not found. Please ensure Java is installed and in PATH.
    exit /b 1
)

REM Set certificate type-specific values
if "%TYPE%"=="development" (
    set ORG_UNIT=Development
    set SAN=dns:%HOSTNAME%,dns:*.localhost,ip:127.0.0.1
) else if "%TYPE%"=="production" (
    set ORG_UNIT=Production
    set SAN=dns:%HOSTNAME%,dns:*.%HOSTNAME%
) else (
    echo Error: Unknown type '%TYPE%'. Use 'development' or 'production'
    exit /b 1
)

echo Creating %TYPE% keystore...
echo   Output: %OUTPUT%
echo   Hostname: %HOSTNAME%
echo   Validity: %VALIDITY% days
echo   Alias: %ALIAS%
echo.

keytool -genkeypair ^
    -alias "%ALIAS%" ^
    -keyalg RSA ^
    -keysize 2048 ^
    -storetype PKCS12 ^
    -keystore "%OUTPUT%" ^
    -storepass "%PASSWORD%" ^
    -validity %VALIDITY% ^
    -dname "CN=%HOSTNAME%, OU=%ORG_UNIT%, O=%ORG%, L=%CITY%, ST=%STATE%, C=%COUNTRY%" ^
    -ext "SAN=%SAN%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Keystore created successfully: %OUTPUT%
    echo.
    echo Keystore details:
    keytool -list -keystore "%OUTPUT%" -storepass "%PASSWORD%"
    echo.

    if "%TYPE%"=="development" (
        echo 📝 Development Notes:
        echo   - This is a self-signed certificate suitable for development only
        echo   - To use in GroovyHttpClient, create a matching truststore
        echo.
    ) else (
        echo 📝 Production Notes:
        echo   - This keystore contains a self-signed certificate
        echo   - For production, submit CSR to your Certificate Authority
        echo.
    )

    echo ⚠️  SECURITY WARNING:
    echo   - Store password securely (environment variable, secrets manager)
    echo   - Never commit keystores to version control
    echo.
) else (
    echo ✗ Error creating keystore
    exit /b 1
)