# check-forbidden-imports.ps1
# Reports 'import akka.' / 'import org.apache.pekko.' occurrences in the
# src/main tree of library-family modules. Exit 0 when clean, 1 when any
# forbidden import remains. During Phases 1-3 of the library/server split
# this is a progress meter (count trends to zero); afterwards it is a
# permanent invariant and belongs in CI.
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

$pattern = '^\s*import\s+(akka\.|org\.apache\.pekko\.)'
$repoRoot = Split-Path -Parent $PSScriptRoot
$totalFindings = 0

foreach ($module in $libraryModules) {
    $srcMain = Join-Path $repoRoot (Join-Path $module "src\main")
    if (-not (Test-Path $srcMain)) {
        Write-Output ("{0}: (no src/main)" -f $module)
        continue
    }
    $hits = Get-ChildItem -Path $srcMain -Recurse -Filter *.scala |
        Select-String -Pattern $pattern
    $count = ($hits | Measure-Object).Count
    $totalFindings += $count
    Write-Output ("{0}: {1}" -f $module, $count)
    foreach ($hit in $hits) {
        $relative = $hit.Path.Substring($repoRoot.Length + 1)
        Write-Output ("  {0}:{1} {2}" -f $relative, $hit.LineNumber, $hit.Line.Trim())
    }
}

Write-Output ""
if ($totalFindings -gt 0) {
    Write-Output ("check-forbidden-imports: FAIL - {0} forbidden import(s) in library modules." -f $totalFindings)
    exit 1
} else {
    Write-Output "check-forbidden-imports: PASS - library modules are Akka/Pekko free."
    exit 0
}
