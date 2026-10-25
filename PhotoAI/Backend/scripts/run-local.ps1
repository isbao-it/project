param(
    [string]$JdkHome = 'C:/develop-tools/JDK/jdk1.8.0_504'
)

$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $backend '.env'

# 仅按 KEY=VALUE 读取，不执行 .env 内容，避免配置文件变成任意命令入口。
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        if ($entry -notmatch '^([A-Z_]+)=(.*)$') {
            throw '.env 格式错误，必须使用 KEY=VALUE'
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($key -notin @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'JWT_SECRET', 'JWT_EXPIRE',
                'WX_APPID', 'WX_SECRET', 'WX_MOCK_ENABLED')) {
            throw '.env 包含不支持的配置键'
        }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
}

foreach ($key in @('DB_PASSWORD', 'JWT_SECRET')) {
    $value = [Environment]::GetEnvironmentVariable($key, 'Process')
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^<.*>$') {
        throw "请在 Backend/.env 或环境变量中设置 $key"
    }
}
try {
    $keyBytes = [Convert]::FromBase64String($env:JWT_SECRET)
} catch {
    throw 'JWT_SECRET 必须是 Base64 编码'
}
if ($keyBytes.Length -lt 32) { throw 'JWT_SECRET 解码后至少需要 32 字节（HS256 要求）' }
if (-not (Test-Path -LiteralPath "$JdkHome/bin/java.exe")) { throw '找不到 JDK 8，请设置 -JdkHome' }

# 只修改本次启动进程环境；密钥不会写回配置文件或输出到日志。
$env:JAVA_HOME = $JdkHome
Push-Location $backend
try {
    & "$backend/mvnw.cmd" -B -ntp spring-boot:run
    $resultCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $resultCode
