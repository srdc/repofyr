# check-server-boundary.ps1
#
# Repository boundary gate for the Repofyr server reactor. Enforces, in order,
# invariants 2 and 3 of AGENTS.md:
#
#   1. Server code is declared in io.repofyr.*, never io.onfhir.*.
#      Importing io.onfhir types is expected and correct - the reusable
#      libraries are consumed. Only DECLARING server code in the library
#      namespace is forbidden.
#   2. Every io.onfhir dependency is versioned with ${onfhir.libs.version}.
#   3. No io.repofyr dependency is versioned with ${onfhir.libs.version},
#      which would silently pin a server module to the library release line.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 parses no-BOM files as
# ANSI. Run it bare - do not pipe its output - because under PowerShell 5.1
# with $ErrorActionPreference = "Stop" any native stderr line becomes a
# terminating NativeCommandError.

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$libsVersionToken = '${onfhir.libs.version}'
$violations = New-Object System.Collections.Generic.List[string]

function Get-RelativePath {
    param([string]$FullPath)
    return $FullPath.Substring($repoRoot.Length).TrimStart('\', '/').Replace('\', '/')
}

# Server modules are discovered rather than listed, so a newly added module
# cannot silently escape the gate by being forgotten here.
$moduleDirs = Get-ChildItem -Path $repoRoot -Directory -Filter 'repofyr-*' |
    Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } |
    Sort-Object Name

if ($moduleDirs.Count -eq 0) {
    Write-Host "check-server-boundary: FAIL - no repofyr-* modules found under $repoRoot."
    exit 1
}

Write-Host "Scanning $($moduleDirs.Count) server module(s)."
Write-Host ""

# ---------------------------------------------------------------------------
# Check 1 - no server code declared in the io.onfhir namespace
# ---------------------------------------------------------------------------

Write-Host "[1/3] Package declarations under src/main"

foreach ($moduleDir in $moduleDirs) {
    $moduleHits = 0

    foreach ($sourceRoot in @('src\main\scala', 'src\main\java')) {
        $rootPath = Join-Path $moduleDir.FullName $sourceRoot
        if (-not (Test-Path $rootPath)) { continue }

        $sourceFiles = Get-ChildItem -Path $rootPath -Recurse -File -Include '*.scala', '*.java'
        foreach ($sourceFile in $sourceFiles) {
            $lineNumber = 0
            foreach ($line in (Get-Content -LiteralPath $sourceFile.FullName)) {
                $lineNumber++
                if ($line -match '^\s*package\s+io\.onfhir(\.|\s|$)') {
                    $relative = Get-RelativePath $sourceFile.FullName
                    $violations.Add("  ${relative}:${lineNumber} $($line.Trim())")
                    $moduleHits++
                }
            }
        }
    }

    if ($moduleHits -gt 0) {
        Write-Host "      $($moduleDir.Name): $moduleHits io.onfhir package declaration(s)"
    }
}

# ---------------------------------------------------------------------------
# Checks 2 and 3 - dependency version property discipline
# ---------------------------------------------------------------------------

Write-Host "[2/3] io.onfhir dependencies use $libsVersionToken"
Write-Host "[3/3] io.repofyr dependencies do not use $libsVersionToken"

$pomFiles = @(Join-Path $repoRoot 'pom.xml')
foreach ($moduleDir in $moduleDirs) {
    $pomFiles += (Join-Path $moduleDir.FullName 'pom.xml')
}

foreach ($pomFile in $pomFiles) {
    $relative = Get-RelativePath $pomFile

    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $false
    $xml.Load($pomFile)

    $ns = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
    $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')

    # Covers both <dependencies> and <dependencyManagement>.
    $dependencies = $xml.SelectNodes('//m:dependency', $ns)

    foreach ($dependency in $dependencies) {
        $groupId = $dependency.SelectSingleNode('m:groupId', $ns)
        $artifactId = $dependency.SelectSingleNode('m:artifactId', $ns)
        $version = $dependency.SelectSingleNode('m:version', $ns)

        if ($null -eq $groupId) { continue }

        $group = $groupId.InnerText.Trim()
        $artifact = if ($null -eq $artifactId) { '<unknown>' } else { $artifactId.InnerText.Trim() }

        # A dependency with no <version> inherits from dependencyManagement,
        # where the managed entry is itself checked by this same loop.
        if ($null -eq $version) { continue }
        $versionText = $version.InnerText.Trim()

        if ($group -eq 'io.onfhir' -and $versionText -ne $libsVersionToken) {
            $violations.Add("  ${relative}: io.onfhir:${artifact} uses '${versionText}', expected '${libsVersionToken}'")
        }

        if ($group -eq 'io.repofyr' -and $versionText -eq $libsVersionToken) {
            $violations.Add("  ${relative}: io.repofyr:${artifact} uses '${libsVersionToken}', expected the server version")
        }
    }
}

# ---------------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------------

Write-Host ""

if ($violations.Count -gt 0) {
    Write-Host "Boundary violations:"
    foreach ($violation in $violations) {
        Write-Host $violation
    }
    Write-Host ""
    Write-Host "check-server-boundary: FAIL - $($violations.Count) boundary violation(s)."
    exit 1
}

Write-Host "check-server-boundary: PASS - server modules stay in io.repofyr.*"

# Exit explicitly: $LASTEXITCODE may still hold a nonzero value from an earlier
# native call, and CI steps using 'shell: pwsh' exit with $LASTEXITCODE.
exit 0
