#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ProjectDirectory,
    [string]$JavaHome,
    [string]$AndroidSdk,
    [string]$SigningDirectory,
    [switch]$Test,
    [switch]$SkipChecks,
    [string[]]$TestFilters = @('*fusion.*', '*peq.*', '*convolver.*'),
    [string[]]$TestClassIncludes = @(),
    [switch]$Offline,
    [switch]$CheckOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$bundleRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$expectedCertificate = 'f31ecf1f043f442b1e97f9c0f989828bc060923abd76534206e279a12b21c496'

function Resolve-BuildDirectory([string]$Value, [string]$Label) {
    if (-not $Value -or -not (Test-Path -LiteralPath $Value -PathType Container)) {
        throw "$Label directory was not found. Pass an explicit path parameter."
    }
    return (Resolve-Path -LiteralPath $Value).ProviderPath
}

function Require-BuildFile([string]$Value, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Value -PathType Leaf)) {
        throw "$Label was not found: $Value"
    }
}

function Get-OwnSubstTarget([string]$Letter) {
    # Read the Unicode device target directly: subst's console output may use a
    # different code page and corrupt Chinese paths before the ownership check.
    if (-not ('DspLocalBuildDosDevice' -as [type])) {
        Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
public static class DspLocalBuildDosDevice {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern uint QueryDosDevice(string name, StringBuilder target, int capacity);
}
'@
    }
    $target = New-Object System.Text.StringBuilder 32768
    $length = [DspLocalBuildDosDevice]::QueryDosDevice(($Letter + ':'), $target, $target.Capacity)
    if ($length -gt 0 -and $target.ToString().StartsWith('\??\', [StringComparison]::Ordinal)) {
        return $target.ToString().Substring(4)
    }
    return $null
}

if (-not $ProjectDirectory) { $ProjectDirectory = Split-Path -Parent $PSScriptRoot }
$projectPath = Resolve-BuildDirectory $ProjectDirectory 'Project'
Require-BuildFile (Join-Path $projectPath 'gradlew.bat') 'Gradle wrapper'
Require-BuildFile (Join-Path $projectPath 'gradle\wrapper\gradle-wrapper.jar') 'Gradle wrapper JAR'

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
}
if (-not $JavaHome -and $env:LOCALAPPDATA) {
    $JavaHome = Join-Path $env:LOCALAPPDATA 'Programs\Java\jdk-17'
}
$javaPath = Resolve-BuildDirectory $JavaHome 'JDK 17'
Require-BuildFile (Join-Path $javaPath 'bin\java.exe') 'Java'
Require-BuildFile (Join-Path $javaPath 'bin\javac.exe') 'Java compiler'
Require-BuildFile (Join-Path $javaPath 'bin\keytool.exe') 'Signing certificate tool'
$javaRelease = Join-Path $javaPath 'release'
Require-BuildFile $javaRelease 'JDK release metadata'
$javaVersionLine = Get-Content -LiteralPath $javaRelease | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
if ([string]$javaVersionLine -notmatch '^JAVA_VERSION="17(?:\.|\")') {
    throw 'This project requires JDK 17. Select it with -JavaHome.'
}

if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -and $env:LOCALAPPDATA) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$sdkPath = Resolve-BuildDirectory $AndroidSdk 'Android SDK'
$platformsPath = Join-Path $sdkPath 'platforms'
$platform35 = @(Get-ChildItem -LiteralPath $platformsPath -Directory -ErrorAction SilentlyContinue | Where-Object {
    $properties = Join-Path $_.FullName 'source.properties'
    (Test-Path -LiteralPath $properties) -and
        (Select-String -LiteralPath $properties -Pattern '^AndroidVersion\.ApiLevel\s*=\s*35\s*$' -Quiet) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar'))
})
if ($platform35.Count -eq 0) { throw 'Android SDK platform 35 is missing or incomplete.' }
Require-BuildFile (Join-Path $sdkPath 'build-tools\35.0.0\apksigner.bat') 'Android build tools 35.0.0'
Require-BuildFile (Join-Path $sdkPath 'build-tools\35.0.0\aapt.exe') 'Android resource tool'
Require-BuildFile (Join-Path $sdkPath 'build-tools\35.0.0\zipalign.exe') 'APK alignment tool'
Require-BuildFile (Join-Path $sdkPath 'ndk\26.3.11579264\source.properties') 'Android NDK 26.3.11579264'
Require-BuildFile (Join-Path $sdkPath 'ndk\26.3.11579264\build\cmake\android.toolchain.cmake') 'NDK CMake toolchain'
Require-BuildFile (Join-Path $sdkPath 'ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe') 'NDK Windows compiler'
Require-BuildFile (Join-Path $sdkPath 'cmake\3.22.1\bin\cmake.exe') 'Android SDK CMake 3.22.1'
Require-BuildFile (Join-Path $sdkPath 'cmake\3.22.1\bin\ninja.exe') 'Android SDK Ninja'

# An existing local.properties takes precedence over ANDROID_HOME. Do not silently
# rewrite source files or let a stale machine-specific SDK path select another SDK.
$localProperties = Join-Path $projectPath 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    $sdkProperty = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($sdkProperty) {
        $declaredSdk = (($sdkProperty -split '=', 2)[1]).Trim().Replace('\:', ':').Replace('\\', '\')
        $declaredSdk = [IO.Path]::GetFullPath($declaredSdk).TrimEnd('\', '/')
        if (-not $declaredSdk.Equals($sdkPath.TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)) {
            throw 'local.properties selects a different SDK. Pass that SDK with -AndroidSdk, or update/remove that local file yourself.'
        }
    }
}

if (-not $SigningDirectory) {
    $signingFolders = @(Get-ChildItem -LiteralPath $bundleRoot -Directory | Where-Object { $_.Name.StartsWith('08 本地签名资料') })
    if ($signingFolders.Count -ne 1) { throw 'The private signing directory is missing or ambiguous. Pass -SigningDirectory.' }
    $SigningDirectory = $signingFolders[0].FullName
}
$signingPath = Resolve-BuildDirectory $SigningDirectory 'Private signing'
$keystorePath = Join-Path $signingPath 'fusion-release.jks'
$signingJsonPath = Join-Path $signingPath 'fusion-signing.json'
Require-BuildFile $keystorePath 'Original release keystore'
Require-BuildFile $signingJsonPath 'Private signing configuration'

if ($Offline) {
    $gradleCache = $env:GRADLE_USER_HOME
    if (-not $gradleCache) { $gradleCache = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
    $gradleDistribution = Join-Path $gradleCache 'wrapper\dists\gradle-8.10.2-bin'
    $cachedLauncher = @(Get-ChildItem -LiteralPath $gradleDistribution -Filter 'gradle-launcher-8.10.2.jar' -File -Recurse -ErrorAction SilentlyContinue)
    if ($cachedLauncher.Count -eq 0) {
        throw 'Offline mode needs the cached Gradle 8.10.2 distribution. No download or build was started.'
    }
}

Write-Host 'Local build environment:'
Write-Host "  Project: $projectPath"
Write-Host "  JDK: $javaPath ($javaVersionLine)"
Write-Host "  Android SDK: $sdkPath"
Write-Host '  Gradle wrapper 8.10.2 / AGP 8.7.3 / NDK 26.3.11579264 / CMake 3.22.1'
Write-Host '  Variant: rootFdroidRelease / package: com.dspfusion.android'
Write-Host '  Original signing files: present (password is not displayed)'
foreach ($platform in $platform35) {
    if ($platform.Name -ne 'android-35') {
        Write-Warning "SDK 35 is in $($platform.Name); the existing SDK may report an inconsistent-package-location warning."
    }
}
if ($CheckOnly) {
    Write-Host 'Read-only environment check passed. No Gradle, mapping, upload, or installation was started.'
    return
}

$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'FUSION_KEYSTORE', 'FUSION_STORE_PASSWORD', 'PATH')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$createdDrive = $null
$locationPushed = $false
$signingData = $null
$certificateOutput = $null
try {
    $env:JAVA_HOME = $javaPath
    $env:ANDROID_HOME = $sdkPath
    $env:ANDROID_SDK_ROOT = $sdkPath
    $env:PATH = (Join-Path $javaPath 'bin') + ';' + $savedEnvironment['PATH']
    $env:FUSION_KEYSTORE = $keystorePath
    try {
        $signingData = Get-Content -LiteralPath $signingJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $env:FUSION_STORE_PASSWORD = [string]$signingData.password
    } catch { throw 'Cannot read the private signing configuration. Its contents have not been displayed.' }
    if ([string]::IsNullOrWhiteSpace($env:FUSION_STORE_PASSWORD)) { throw 'The signing password is empty. Refusing debug-signature fallback.' }

    # Pass the password through an environment variable, never through visible CLI arguments.
    $certificateOutput = @(& (Join-Path $javaPath 'bin\keytool.exe') '-J-Duser.language=en' '-J-Duser.country=US' '-list' '-v' '-keystore' $keystorePath '-alias' 'fusion' '-storepass:env' 'FUSION_STORE_PASSWORD' 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'The original keystore/password/alias could not be verified. Build aborted.' }
    $certificateText = $certificateOutput -join "`n"
    if ($certificateText -notmatch 'SHA256:\s*([A-Fa-f0-9:]+)') { throw 'The release certificate fingerprint could not be read. Build aborted.' }
    $certificateSha = $Matches[1].Replace(':', '').ToLowerInvariant()
    if ($certificateSha -ne $expectedCertificate) { throw 'The signing certificate differs from the released application. Build aborted.' }
    Write-Host "  Release certificate verified: $certificateSha"

    $usedLetters = @([IO.DriveInfo]::GetDrives() | ForEach-Object { $_.Name.Substring(0, 1).ToUpperInvariant() })
    $usedLetters += @(Get-PSDrive -PSProvider FileSystem | ForEach-Object { $_.Name.ToUpperInvariant() })
    foreach ($letter in [char[]]'ZYXWVUTSRQP') {
        $candidate = [string]$letter
        if ($usedLetters -contains $candidate -or (Get-OwnSubstTarget $candidate)) { continue }
        & "$env:SystemRoot\System32\subst.exe" ($candidate + ':') $projectPath
        if ($LASTEXITCODE -ne 0) { continue }
        $createdDrive = $candidate
        break
    }
    if (-not $createdDrive) { throw 'No free temporary drive letter was available. Existing mappings were not changed.' }
    Write-Host "  Temporary source drive: ${createdDrive}: (removed after this run)"
    Push-Location ($createdDrive + ':\')
    $locationPushed = $true

    $gradleArguments = @('--no-daemon', '--max-workers=2')
    if ($Offline) { $gradleArguments += '--offline' }
    $gradleArguments += ':app:assembleRootFdroidRelease'
    if ($SkipChecks) {
        if ($Test) { throw 'SkipChecks cannot be combined with Test.' }
        $gradleArguments += @('-x', 'test', '-x', 'lint', '-x', 'check')
    }
    if ($Test) {
        $gradleArguments += ':app:testRootFdroidReleaseUnitTest'
        foreach ($testFilter in $TestFilters) { $gradleArguments += @('--tests', $testFilter) }
        if ($TestClassIncludes.Count -gt 0) { $gradleArguments += "-PfusionTestClassIncludes=$($TestClassIncludes -join ',')" }
    }
    & '.\gradlew.bat' @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE. No upload or installation was performed." }

    $apkDirectory = Join-Path $projectPath 'app\build\outputs\apk\rootFdroid\release'
    $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.dspfusion.android' -or $metadata.variantName -ne 'rootFdroidRelease') { throw 'Unexpected APK build metadata.' }
    $universal = @($metadata.elements | Where-Object { $_.type -eq 'UNIVERSAL' })
    if ($universal.Count -ne 1 -or [IO.Path]::GetFileName($universal[0].outputFile) -ne $universal[0].outputFile) { throw 'Invalid universal APK metadata.' }
    $apkFiles = @(Get-Item -LiteralPath (Join-Path $apkDirectory $universal[0].outputFile))
    if ($apkFiles.Count -ne 1) { throw 'Expected exactly one universal release APK. Check the Gradle output.' }
    & (Join-Path $sdkPath 'build-tools\35.0.0\apksigner.bat') verify --print-certs $apkFiles[0].FullName
    if ($LASTEXITCODE -ne 0) { throw 'The generated APK did not pass signature verification.' }
    Write-Host "APK: $($apkFiles[0].FullName)"
    Write-Host "SHA256: $((Get-FileHash -LiteralPath $apkFiles[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
    Write-Host 'Local build completed. Nothing was uploaded or installed.'
} finally {
    if ($locationPushed) { Pop-Location }
    if ($createdDrive) {
        $currentTarget = Get-OwnSubstTarget $createdDrive
        if ($currentTarget -and $currentTarget.TrimEnd('\', '/').Equals($projectPath.TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)) {
            & "$env:SystemRoot\System32\subst.exe" ($createdDrive + ':') '/D'
        } else {
            Write-Warning 'The temporary mapping changed during the build; it was left untouched.'
        }
    }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $signingData = $null
    $certificateOutput = $null
    $certificateText = $null
}
