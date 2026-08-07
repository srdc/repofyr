# check-staged-release.ps1
#
# Verifies a locally staged Repofyr server release before anything is
# published. Run against the file repository produced by:
#
#   mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<path>
#
# For every published coordinate this asserts:
#   - the POM exists and declares GPL-3.0, and does NOT declare Apache-2.0
#     (the server is GPL; the reusable io.onfhir libraries are Apache, and a
#     flip in either direction is a release-blocking defect)
#   - the binary, -sources and -javadoc JARs exist
#   - the binary JAR packages META-INF/LICENSE
#   - every file carries a valid detached GPG signature
#
# Keep this file ASCII-only: Windows PowerShell 5.1 parses no-BOM files as
# ANSI. Run it bare - do not pipe its output - because under PowerShell 5.1
# with $ErrorActionPreference = "Stop" any native stderr line becomes a
# terminating NativeCommandError.

param(
    [Parameter(Mandatory = $true)][string]$RepositoryPath,
    [string]$Version = "4.0.0",
    [switch]$SkipSignatures
)

$ErrorActionPreference = "Stop"

# Keep in sync with the reactor <modules> list. A missing entry here means an
# artifact ships unverified, so adding a module means adding a row.
$artifacts = [ordered]@{
    'repofyr-parent'            = 'pom'
    'repofyr-event_2.13'        = 'jar'
    'repofyr-core_2.13'         = 'jar'
    'repofyr-operations_2.13'   = 'jar'
    'repofyr-kafka_2.13'        = 'jar'
    'repofyr-server-r4_2.13'    = 'jar'
    'repofyr-server-r5_2.13'    = 'jar'
    'repofyr-server-stu3_2.13'  = 'jar'
}

$failures = New-Object System.Collections.Generic.List[string]

if (-not (Test-Path $RepositoryPath)) {
    Write-Host "check-staged-release: FAIL - repository path not found: $RepositoryPath"
    exit 1
}

$resolvedRepo = (Resolve-Path $RepositoryPath).Path

function Test-Signature {
    param([string]$FilePath)

    if ($SkipSignatures) { return }

    $signature = "$FilePath.asc"
    if (-not (Test-Path $signature)) {
        $script:failures.Add("missing signature: $(Split-Path -Leaf $signature)")
        return
    }

    # gpg writes its verdict to stderr; tolerate that without letting a
    # non-zero-exit convention abort the whole script.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & gpg --batch --verify $signature $FilePath 2>&1 | Out-Null
    $gpgExit = $LASTEXITCODE
    $ErrorActionPreference = $previous

    if ($gpgExit -ne 0) {
        $script:failures.Add("bad signature: $(Split-Path -Leaf $signature)")
    }
}

Write-Host "Repository : $resolvedRepo"
Write-Host "Version    : $Version"
Write-Host "Signatures : $(if ($SkipSignatures) { 'SKIPPED' } else { 'verified' })"
Write-Host ""

foreach ($artifactId in $artifacts.Keys) {
    $packaging = $artifacts[$artifactId]
    $artifactDir = Join-Path $resolvedRepo "io\repofyr\$artifactId\$Version"

    Write-Host "$artifactId ($packaging)"

    if (-not (Test-Path $artifactDir)) {
        $failures.Add("missing directory: io/repofyr/$artifactId/$Version")
        Write-Host "    MISSING"
        continue
    }

    # --- POM: presence and license metadata -------------------------------
    $pomPath = Join-Path $artifactDir "$artifactId-$Version.pom"
    if (-not (Test-Path $pomPath)) {
        $failures.Add("missing POM: $artifactId-$Version.pom")
    }
    else {
        $pomText = Get-Content -LiteralPath $pomPath -Raw

        if ($pomText -notmatch 'GNU General Public License') {
            $failures.Add("$artifactId POM does not declare the GNU General Public License")
        }
        if ($pomText -match 'Apache License') {
            $failures.Add("$artifactId POM declares an Apache License - the server is GPL-3.0")
        }
        if ($pomText -match '\$\{revision\}') {
            $failures.Add("$artifactId POM contains an unresolved " + '${revision}' + " - flatten did not run")
        }

        Test-Signature $pomPath
        Write-Host "    pom"
    }

    if ($packaging -ne 'jar') { continue }

    # --- JARs: presence, packaged LICENSE, signatures ---------------------
    $mainJar = Join-Path $artifactDir "$artifactId-$Version.jar"

    foreach ($classifier in @('', '-sources', '-javadoc')) {
        $jarPath = Join-Path $artifactDir "$artifactId-$Version$classifier.jar"
        $label = if ($classifier -eq '') { 'jar' } else { $classifier.TrimStart('-') }

        if (-not (Test-Path $jarPath)) {
            $failures.Add("missing artifact: $artifactId-$Version$classifier.jar")
            continue
        }

        Test-Signature $jarPath
        Write-Host "    $label"
    }

    if (Test-Path $mainJar) {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $entries = & jar tf $mainJar
        $ErrorActionPreference = $previous

        if ($entries -notcontains 'META-INF/LICENSE') {
            $failures.Add("$artifactId does not package META-INF/LICENSE")
        }
    }
}

Write-Host ""

if ($failures.Count -gt 0) {
    Write-Host "Failures:"
    foreach ($failure in $failures) {
        Write-Host "  $failure"
    }
    Write-Host ""
    Write-Host "check-staged-release: FAIL - $($failures.Count) problem(s) found."
    exit 1
}

Write-Host "check-staged-release: PASS - $($artifacts.Count) $Version artifacts verified."

# Exit explicitly: $LASTEXITCODE may still hold a nonzero value from gpg or
# jar, and CI steps using 'shell: pwsh' exit with $LASTEXITCODE.
exit 0
