<#
 Creates test merchants + TEST API keys through the gateway and writes loadtest/api_keys.csv
 (columns: key_id,key_secret,basic_auth). The file is git-ignored: never commit it.

 powershell -ExecutionPolicy Bypass -File .\scripts\generate-load-test-keys.ps1 -Count 10

 The gateway limits signup/login to 30 attempts per minute per IP (app.rate-limit.use-case.auth),
 and each merchant needs 2 of them, so the script waits between merchants.
#>
param(
    [string]$Gateway = "http://localhost:8081",
    [int]$Count = 10,
    [int]$DelaySeconds = 5,
    [string]$Out = "loadtest/api_keys.csv"
)
$ErrorActionPreference = "Stop"
$password = "Passw0rd!" + [guid]::NewGuid().ToString("N").Substring(0, 8)
New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null
$rows = @("key_id,key_secret,basic_auth")
for ($i = 1; $i -le $Count; $i++) {
    $email = "loadtest.$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())@example.com"
    try {
        $null = Invoke-RestMethod -Method Post -Uri "$Gateway/v1/auth/signup" -ContentType "application/json" `
            -Body (@{ name = "Load Test $i"; email = $email; password = $password; businessName = "Load Test $i"; businessType = "PROPRIETORSHIP" } | ConvertTo-Json)
        $login = Invoke-RestMethod -Method Post -Uri "$Gateway/v1/auth/login" -ContentType "application/json" `
            -Body (@{ email = $email; password = $password } | ConvertTo-Json)
        $key = Invoke-RestMethod -Method Post -Uri "$Gateway/v1/merchants/api-keys" -ContentType "application/json" `
            -Headers @{ Authorization = "Bearer $($login.accessToken)" } -Body (@{ environment = "TEST" } | ConvertTo-Json)
        $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($key.keyId):$($key.keySecret)"))
        $rows += "$($key.keyId),$($key.keySecret),$basic"
        Write-Host "created merchant $i/$Count"
    } catch { Write-Warning "merchant $i failed: $($_.Exception.Message)" }
    if ($i -lt $Count) { Start-Sleep -Seconds $DelaySeconds }
}
$rows | Set-Content -Path $Out -Encoding ASCII
Write-Host "wrote $($rows.Count - 1) API keys to $Out (git-ignored)"
