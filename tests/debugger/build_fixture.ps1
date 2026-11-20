param([string]$Ndk = 'D:\Android\Sdk\ndk\28.2.13676358')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$compiler = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
foreach ($target in @(@('arm64-v8a','aarch64-linux-android28'),@('x86_64','x86_64-linux-android28'))) {
    $directory = Join-Path $repo "app/src/debug/jniLibs/$($target[0])"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    & $compiler "--target=$($target[1])" '-fPIE' '-pie' '-O0' '-fno-omit-frame-pointer' '-pthread' (Join-Path $PSScriptRoot 'fixture.c') '-o' (Join-Path $directory 'libqingwei_debug_fixture.so')
    if ($LASTEXITCODE -ne 0) { throw 'Debug fixture compilation failed' }
}
