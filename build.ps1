param(
    [switch]$RunSelfTest
)

$ErrorActionPreference = 'Stop'

$javacCommand = Get-Command javac -ErrorAction SilentlyContinue
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $javacCommand -or $null -eq $javaCommand) {
    throw 'Java 17 JDK is required. Configure JAVA_HOME and add its bin directory to PATH.'
}

$versionOutput = & $javacCommand.Source -version 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to run javac.'
}
Write-Host $versionOutput

$sourceFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') `
        -Recurse -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName
)
if ($sourceFiles.Count -eq 0) {
    throw 'No Java source files were found under src.'
}

$outputDirectory = Join-Path $PSScriptRoot 'out'
if (Test-Path -LiteralPath $outputDirectory) {
    Remove-Item -LiteralPath $outputDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $outputDirectory | Out-Null

& $javacCommand.Source -Xlint:all -d $outputDirectory $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed with exit code $LASTEXITCODE."
}

Write-Host "Compiled $($sourceFiles.Count) Java source files into $outputDirectory"

if ($RunSelfTest) {
    & $javaCommand.Source -ea -cp $outputDirectory ProjectSelfTest
    if ($LASTEXITCODE -ne 0) {
        throw "ProjectSelfTest failed with exit code $LASTEXITCODE."
    }
}
