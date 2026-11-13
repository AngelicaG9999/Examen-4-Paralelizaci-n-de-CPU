param(
    [switch]$SoloCompilar
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    Get-Command javac -ErrorAction Stop | Out-Null
    $directorioSalida = Join-Path $PSScriptRoot 'target\classes'
    New-Item -ItemType Directory -Path $directorioSalida -Force | Out-Null
    $fuentesJava = @(Get-ChildItem -LiteralPath 'src' -Recurse -Filter '*.java' |
        Select-Object -ExpandProperty FullName)

    & javac --release 21 -encoding UTF-8 -cp 'lib/*' -d $directorioSalida @fuentesJava
    if ($LASTEXITCODE -ne 0) {
        throw 'No se pudo compilar el reproductor. Revise los errores anteriores.'
    }
    Write-Host 'Reproductor compilado correctamente. Clase principal: App.'

    if (-not $SoloCompilar) {
        & java -cp 'target/classes;lib/*' App
        if ($LASTEXITCODE -ne 0) {
            throw 'El reproductor termino con un error.'
        }
    }
} finally {
    Pop-Location
}