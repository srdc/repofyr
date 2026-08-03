# Generates the aggregate third-party report for the library reactor and
# rejects dependencies that do not advertise an approved license or have an
# explicit reviewed override. ASCII-only for Windows PowerShell 5.1.

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$modules = @(
    "onfhir-common", "onfhir-client", "onfhir-path", "onfhir-query",
    "onfhir-config", "onfhir-expression", "onfhir-validation",
    "onfhir-template-engine", "onfhir-r4"
)
$moduleList = $modules -join ","
$report = Join-Path $repoRoot "target\generated-sources\license\THIRD-PARTY.txt"
$allowlistFile = Join-Path $repoRoot "config\library-license-allowlist.txt"
$overrideFile = Join-Path $repoRoot "config\library-license-overrides.txt"

$mavenArgs = @(
    "-B", "-pl", $moduleList, "-am",
    "org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party",
    "-Dlicense.excludedScopes=test,provided"
)
& mvn @mavenArgs
if ($LASTEXITCODE -ne 0) {
    throw "Dependency license report generation failed."
}
if (-not (Test-Path $report)) {
    throw "Dependency license report was not generated at $report"
}

$overrides = @{}
Get-Content $overrideFile | Where-Object { $_ -and -not $_.StartsWith("#") } |
    ForEach-Object {
        $parts = $_ -split "=", 2
        $overrides[$parts[0].Trim()] = $parts[1].Trim()
    }

$licensePatterns = @{
    "Apache-2.0" = '(?i)Apache'
    "BSD-2-Clause" = '(?i)BSD'
    "BSD-3-Clause" = '(?i)BSD'
    "MIT" = '(?i)MIT'
    "EPL-1.0" = '(?i)Eclipse Public License'
    "MPL-1.1" = '(?i)MPL'
}
$approvedPatterns = @()
Get-Content $allowlistFile | Where-Object { $_ -and -not $_.StartsWith("#") } |
    ForEach-Object {
        $licenseId = $_.Trim()
        if (-not $licensePatterns.ContainsKey($licenseId)) {
            throw "No report-name mapping is defined for allowlisted license $licenseId"
        }
        $approvedPatterns += $licensePatterns[$licenseId]
    }
$failures = @()
$dependencies = 0

foreach ($line in Get-Content $report) {
    if ($line -notmatch '\(([^():]+):([^():]+):([^() ]+) - ') {
        continue
    }
    $coordinate = "{0}:{1}:{2}" -f $Matches[1], $Matches[2], $Matches[3]
    if ($coordinate.StartsWith("io.onfhir:")) {
        continue
    }
    $dependencies++
    $approved = $false
    foreach ($pattern in $approvedPatterns) {
        if ($line -match $pattern) {
            $approved = $true
            break
        }
    }
    if (-not $approved -and $overrides.ContainsKey($coordinate)) {
        $approved = $true
        Write-Output ("OVERRIDE {0} -> {1}" -f $coordinate, $overrides[$coordinate])
    }
    if (-not $approved) {
        $failures += $line.Trim()
    }
}

if ($failures.Count -gt 0) {
    Write-Output "Unapproved dependency licenses:"
    $failures | ForEach-Object { Write-Output ("  " + $_) }
    exit 1
}

Write-Output ("check-library-dependency-licenses: PASS - {0} external dependencies reviewed." -f $dependencies)
