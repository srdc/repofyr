# Compares current reusable artifacts with the last public 3.3 release using
# MiMa CLI. The committed baseline represents intentional breaks reconciled
# with the migration table. ASCII-only for Windows PowerShell 5.1.

param(
    [string]$PreviousVersion = "3.3",
    [switch]$UpdateBaseline,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$baseline = Join-Path $repoRoot "docs\compatibility\mima-3.3-accepted.txt"
$scratch = Join-Path ([IO.Path]::GetTempPath()) ("onfhir-mima-" + [Guid]::NewGuid().ToString("N"))
$oldDir = Join-Path $scratch "old"
New-Item -ItemType Directory -Path $oldDir -Force | Out-Null

$artifacts = [ordered]@{
    "onfhir-common" = "onfhir-common_2.13"
    "onfhir-client" = "onfhir-client_2.13"
    "onfhir-path" = "onfhir-path_2.13"
    "onfhir-query" = "onfhir-query_2.13"
    "onfhir-config" = "onfhir-config_2.13"
    "onfhir-expression" = "onfhir-expression_2.13"
    "onfhir-validation" = "onfhir-validation_2.13"
    "onfhir-template-engine" = "onfhir-template-engine"
    "onfhir-r4" = "onfhir-r4_2.13"
}
$newArtifacts = @("onfhir-query_2.13", "onfhir-template-engine")

$modules = ($artifacts.Keys -join ",")
Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & mvn -B -pl $modules -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Current library artifact build failed." }
    }

    $reportLines = @(
        "# MiMa accepted compatibility report",
        "# Baseline: io.onfhir reusable artifacts $PreviousVersion",
        "# Every reported break must have a corresponding migration-table entry.",
        "# Reconciliation: docs/compatibility/mima-3.3-reconciliation.md",
        ""
    )
    foreach ($entry in $artifacts.GetEnumerator()) {
        $module = $entry.Key
        $artifact = $entry.Value
        $currentJar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "$module\target") -Filter "$artifact-*.jar" |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $currentJar) { throw "Current JAR not found for $artifact" }

        if ($newArtifacts -contains $artifact) {
            $reportLines += "## $artifact"
            $reportLines += "NEW-ARTIFACT: no $PreviousVersion artifact was available"
            $reportLines += ""
            continue
        }

        $copyArgs = @(
            "-q", "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy",
            "-Dartifact=io.onfhir:${artifact}:$PreviousVersion",
            "-DoutputDirectory=$oldDir",
            "-Dmdep.stripVersion=true"
        )
        & mvn @copyArgs
        if ($LASTEXITCODE -ne 0) {
            $reportLines += "## $artifact"
            $reportLines += "NEW-ARTIFACT: no $PreviousVersion artifact was available"
            $reportLines += ""
            continue
        }

        $oldJar = Join-Path $oldDir "$artifact.jar"
        $savedErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $mimaOutput = @(& cs launch com.typesafe:mima-cli_3:1.1.5 -- $oldJar $currentJar.FullName 2>&1)
        $mimaExit = $LASTEXITCODE
        $ErrorActionPreference = $savedErrorPreference
        $normalized = $mimaOutput | ForEach-Object {
            $_.ToString().Replace($oldJar, "<OLD-JAR>").Replace($currentJar.FullName, "<CURRENT-JAR>")
        } | Where-Object { $_ -notmatch '^\s*$' -and $_ -notmatch '^NOTE: Picked up JDK_JAVA_OPTIONS:' }
        $reportLines += "## $artifact"
        if ($normalized.Count -eq 0 -and $mimaExit -eq 0) {
            $reportLines += "COMPATIBLE"
        } else {
            $reportLines += $normalized
        }
        $reportLines += ""
    }
} finally {
    Pop-Location
}

$report = ($reportLines -join [Environment]::NewLine).TrimEnd() + [Environment]::NewLine
if ($UpdateBaseline) {
    $parent = Split-Path -Parent $baseline
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Set-Content -LiteralPath $baseline -Value $report -Encoding UTF8
    Write-Output ("Updated MiMa baseline: {0}" -f $baseline)
    exit 0
}
if (-not (Test-Path $baseline)) {
    throw "MiMa baseline is missing. Review and create it with -UpdateBaseline."
}
$expected = Get-Content -LiteralPath $baseline -Raw
$expectedNormalized = ($expected -replace "`r`n", "`n").TrimEnd()
$reportNormalized = ($report -replace "`r`n", "`n").TrimEnd()
if ($reportNormalized -ne $expectedNormalized) {
    Write-Output "MiMa report differs from the accepted baseline."
    Write-Output "Run with -UpdateBaseline only after reconciling changes with the migration table."
    exit 1
}
Write-Output "check-binary-compatibility: PASS"
