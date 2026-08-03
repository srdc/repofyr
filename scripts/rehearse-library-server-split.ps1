# Builds disposable library-only and server-only reactors against one isolated
# Maven repository. It never rewrites the working copy. ASCII-only for Windows
# PowerShell 5.1.

param([switch]$SkipServerTests)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$workRoot = Join-Path ([IO.Path]::GetTempPath()) ("onfhir-split-" + [Guid]::NewGuid().ToString("N"))
$libraryRoot = Join-Path $workRoot "onfhir-libs"
$serverRoot = Join-Path $workRoot "repofyr"
$localRepo = Join-Path $workRoot "m2"
$libraryModules = @(
    "onfhir-common", "onfhir-client", "onfhir-path", "onfhir-query",
    "onfhir-config", "onfhir-expression", "onfhir-validation",
    "onfhir-template-engine", "onfhir-r4"
)
$serverModules = @(
    "onfhir-event", "onfhir-core", "onfhir-operations", "onfhir-kafka",
    "onfhir-server-r4", "onfhir-server-r5", "onfhir-server-stu3"
)

function Copy-ModuleTree([string]$source, [string]$destination) {
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath $source -Force | Where-Object { $_.Name -ne "target" } |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $destination -Recurse -Force }
}

function Set-ReactorModules([xml]$pom, [string[]]$modules) {
    $namespace = $pom.Project.NamespaceURI
    $manager = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $manager.AddNamespace("m", $namespace)
    $modulesNode = $pom.SelectSingleNode("/m:project/m:modules", $manager)
    $modulesNode.RemoveAll()
    foreach ($module in $modules) {
        $node = $pom.CreateElement("module", $namespace)
        $node.InnerText = $module
        [void]$modulesNode.AppendChild($node)
    }
}

New-Item -ItemType Directory -Path $libraryRoot, $serverRoot, $localRepo -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot "pom.xml") -Destination (Join-Path $libraryRoot "pom.xml")
Copy-Item -LiteralPath (Join-Path $repoRoot "pom.xml") -Destination (Join-Path $serverRoot "pom.xml")
foreach ($module in $libraryModules) {
    Copy-ModuleTree (Join-Path $repoRoot $module) (Join-Path $libraryRoot $module)
}
foreach ($module in $serverModules) {
    Copy-ModuleTree (Join-Path $repoRoot $module) (Join-Path $serverRoot $module)
}

[xml]$libraryPom = Get-Content -LiteralPath (Join-Path $libraryRoot "pom.xml") -Raw
Set-ReactorModules $libraryPom $libraryModules
$libraryPom.project.artifactId = "onfhir-libs-parent"
$libraryPom.project.name = "onFHIR Reusable Libraries"
$libraryPom.project.description = "Reusable, transport-neutral onFHIR libraries."
$libraryPom.Save((Join-Path $libraryRoot "pom.xml"))

foreach ($module in $libraryModules) {
    $pomPath = Join-Path $libraryRoot (Join-Path $module "pom.xml")
    [xml]$modulePom = Get-Content -LiteralPath $pomPath -Raw
    $modulePom.project.parent.artifactId = "onfhir-libs-parent"
    $modulePom.Save($pomPath)
}

[xml]$serverPom = Get-Content -LiteralPath (Join-Path $serverRoot "pom.xml") -Raw
Set-ReactorModules $serverPom $serverModules
$serverPom.Save((Join-Path $serverRoot "pom.xml"))

$localRepoArg = "-Dmaven.repo.local=$localRepo"
Push-Location $libraryRoot
try {
    & mvn -B clean install -DskipTests $localRepoArg
    if ($LASTEXITCODE -ne 0) { throw "Library-only reactor failed." }
} finally {
    Pop-Location
}

Push-Location $serverRoot
try {
    $serverArgs = @("-B", "-pl", "onfhir-server-r4", "-am")
    if ($SkipServerTests) { $serverArgs += "-DskipTests"; $serverArgs += "compile" }
    else { $serverArgs += "test" }
    $serverArgs += $localRepoArg
    & mvn @serverArgs
    if ($LASTEXITCODE -ne 0) { throw "Server-only reactor failed." }
} finally {
    Pop-Location
}

Write-Output "rehearse-library-server-split: PASS"
Write-Output ("Disposable reactors: {0}" -f $workRoot)
Write-Output "The disposable directory is retained for inspection; remove it after review."
