param([string]$Ndk = 'D:\Android\Sdk\ndk\28.2.13676358')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$compiler = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$target = Join-Path $repo 'app/src/androidTest/assets/analysis-fixture.so'
& $compiler '--target=aarch64-linux-android28' '-shared' '-fPIC' '-O1' '-Wl,-soname,analysis-fixture.so' (Join-Path $PSScriptRoot 'fixture.c') '-o' $target
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
Get-FileHash -LiteralPath $target -Algorithm SHA256
