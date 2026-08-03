# check-forbidden-imports.ps1
# Rejects Akka/Pekko imports, fully qualified source references, resource
# namespaces, and class names in library-family production trees.
#
# Usage: powershell -File scripts\check-forbidden-imports.ps1
# ASCII-only on purpose (Windows PowerShell 5.1 parses no-BOM files as ANSI).

$ErrorActionPreference = "Stop"

$libraryModules = @(
    "onfhir-common",
    "onfhir-client",
    "onfhir-path",
    "onfhir-query",
    "onfhir-config",
    "onfhir-expression",
    "onfhir-validation",
    "onfhir-template-engine",
    "onfhir-r4"
)

$sourcePattern = '(?<![A-Za-z0-9_])(akka\.|org\.apache\.pekko\.)'
$resourcePattern = '(?i)(?<![A-Za-z0-9_])(akka\.|pekko\.|org\.apache\.pekko\.)'
$sourceExtensions = @(".scala", ".java")
$repoRoot = Split-Path -Parent $PSScriptRoot
$totalFindings = 0

foreach ($module in $libraryModules) {
    $srcMain = Join-Path $repoRoot (Join-Path $module "src\main")
    if (-not (Test-Path $srcMain)) {
        Write-Output ("{0}: (no src/main)" -f $module)
        continue
    }
    $sourceRoot = Join-Path $srcMain "scala"
    $javaRoot = Join-Path $srcMain "java"
    $sourceFiles = @()
    foreach ($root in @($sourceRoot, $javaRoot)) {
        if (Test-Path $root) {
            $sourceFiles += Get-ChildItem -Path $root -Recurse -File |
                Where-Object { $sourceExtensions -contains $_.Extension }
        }
    }
    $sourceHits = @($sourceFiles | Select-String -Pattern $sourcePattern)

    $resourceRoot = Join-Path $srcMain "resources"
    $resourceHits = @()
    if (Test-Path $resourceRoot) {
        $resourceHits = @(Get-ChildItem -Path $resourceRoot -Recurse -File |
            Select-String -Pattern $resourcePattern)
    }

    $hits = @($sourceHits) + @($resourceHits)
    $totalFindings += $hits.Count
    Write-Output ("{0}: {1}" -f $module, $hits.Count)
    foreach ($hit in $hits) {
        $relative = $hit.Path.Substring($repoRoot.Length + 1)
        Write-Output ("  {0}:{1} {2}" -f $relative, $hit.LineNumber, $hit.Line.Trim())
    }
}

Write-Output ""
if ($totalFindings -gt 0) {
    Write-Output ("check-forbidden-imports: FAIL - {0} forbidden source/resource reference(s) in library modules." -f $totalFindings)
    exit 1
} else {
    Write-Output "check-forbidden-imports: PASS - library modules are Akka/Pekko free."
    exit 0
}
