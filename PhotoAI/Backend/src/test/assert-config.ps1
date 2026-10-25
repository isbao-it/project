$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$configLines = Get-Content -LiteralPath "$backend/src/main/resources/application.yml" -Encoding UTF8
$passwords = @($configLines | Where-Object { $_ -match '^\s*password:' })
$secrets = @($configLines | Where-Object { $_ -match '^\s*secret:' })
if ($passwords.Count -ne 1 -or $passwords[0].Trim() -ne 'password: ${DB_PASSWORD}') {
    throw 'FAIL T4.1/T4.3: 数据库口令必须使用无默认值的环境变量'
}
if ($secrets.Count -ne 1 -or $secrets[0].Trim() -ne 'secret: ${JWT_SECRET}') {
    throw 'FAIL T4.2: JWT 密钥必须使用无默认值的环境变量'
}
$example = Get-Content -LiteralPath "$backend/.env.example" -Encoding UTF8
foreach ($line in $example) {
    if ($line.Trim() -and -not $line.Trim().StartsWith('#') -and $line -notmatch '^[A-Z_]+=<[^>]+>$') {
        throw 'FAIL T4.4: 示例文件只能填写占位符'
    }
}
if (-not ($example -match '^DB_PASSWORD=') -or -not ($example -match '^JWT_SECRET=')) {
    throw 'FAIL T4.4: 示例缺少必填环境变量'
}
$ignored = Get-Content -LiteralPath "$backend/../.gitignore" -Encoding UTF8
if ($ignored -notcontains '.env') {
    throw 'FAIL T4.4: .env 必须被忽略'
}
Write-Output 'PASS T4.1 T4.2 T4.3 T4.4'
