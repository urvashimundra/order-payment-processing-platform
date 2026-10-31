<#
 verify-all.ps1  -  one-file end-to-end verification of the whole payment platform.

 Run from the project root (PowerShell):
     powershell -ExecutionPolicy Bypass -File .\verify-all.ps1

 Options
     -Gateway <url>      Gateway URL. Default: auto-detects http://localhost:8081 then :8080
     -Postgres <name>    Docker container name of Postgres (default pay-postgres)
     -RunTests           Also run `mvnw test` (unit tests) in every module first
     -SkipRateLimit      Skip the login brute-force test (it burns the per-minute login quota)

 What it checks:  infrastructure, service health, signup/login, API keys, orders, UPI + card
 payments, idempotency, tenant isolation, header-forgery protection, error codes (no 500s for
 client mistakes), outbox -> Kafka -> webhook delivery with a verifiable HMAC signature,
 state-transition log, and the rate limiter.
#>
param(
    [string]$Gateway  = "",
    [string]$Postgres = "pay-postgres",
    [switch]$RunTests,
    [switch]$SkipRateLimit
)

$ErrorActionPreference = "Continue"
$script:pass = 0; $script:fail = 0; $script:results = @(); $script:failed = @()

function Section($title) { Write-Host "`n== $title" -ForegroundColor Cyan }
function Check($name, $ok, $detail = "") {
    $ok = [bool]$ok
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
    else     { $script:fail++; $script:failed += $name; Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red }
    $script:results += [pscustomobject]@{ Check = $name; Ok = $ok; Detail = "$detail" }
}
function Info($msg) { Write-Host "         $msg" -ForegroundColor DarkGray }

# Calls an API; never throws. Returns @{ Status; Body; Raw }
function Call($method, $url, $headers = @{}, $body = $null) {
    $p = @{ Method = $method; Uri = $url; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30; ErrorAction = "Stop" }
    if ($null -ne $body) { $p.Body = ($body | ConvertTo-Json -Depth 8); $p.ContentType = "application/json" }
    $raw = ""; $status = 0
    try {
        $r = Invoke-WebRequest @p
        $status = [int]$r.StatusCode; $raw = $r.Content
        # PowerShell 5.1 returns bytes for vendor JSON types such as application/vnd.spring-boot.actuator.v3+json
        if ($raw -is [byte[]]) { $raw = [Text.Encoding]::UTF8.GetString($raw) }
    } catch {
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $raw = $_.ErrorDetails.Message }
        elseif ($_.Exception.Response) {
            try { $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream()); $raw = $sr.ReadToEnd() } catch {}
        }
    }
    $obj = $null
    if ($raw) { try { $obj = $raw | ConvertFrom-Json } catch { $obj = $null } }
    return @{ Status = $status; Body = $obj; Raw = $raw }
}

function Db($database, $sql) {
    $out = docker exec $Postgres psql -h 127.0.0.1 -U postgres -d $database -tAc $sql 2>$null
    return (($out | Out-String).Trim())
}

function Wait-Until([scriptblock]$condition, [int]$timeoutSec = 40, [int]$everySec = 2) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $condition) { return $true }
        Start-Sleep -Seconds $everySec
    }
    return $false
}

function Basic($keyId, $secret) {
    return @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${keyId}:${secret}")) }
}

# ------------------------------------------------------------------ 0. optional unit tests
if ($RunTests) {
    Section "0) Maven unit tests (a few minutes the first time)"
    foreach ($m in "common-lib","config-service","discovery-service","merchant-service","payment-service","operations-service","vault-service","api-gateway-service") {
        Push-Location $m
        if ($m -eq "common-lib") { & .\mvnw.cmd -q install 2>&1 | Out-File "..\test-$m.log" -Encoding utf8 }
        else                     { & .\mvnw.cmd -q test    2>&1 | Out-File "..\test-$m.log" -Encoding utf8 }
        $ok = ($LASTEXITCODE -eq 0); Pop-Location
        Check "unit tests: $m" $ok "(see test-$m.log)"
    }
}

# ------------------------------------------------------------------ 1. infrastructure
Section "1) Infrastructure (Docker)"
$running = (docker ps --format "{{.Names}} {{.Image}}" 2>$null) -join "`n"
Check "Docker is reachable" ($LASTEXITCODE -eq 0)
foreach ($c in "postgres","redis","kafka","zipkin") { Check "$c container running" ($running -match $c) }

# ------------------------------------------------------------------ 2. health
Section "2) Service health"
if (-not $Gateway) {
    foreach ($candidate in "http://localhost:8081","http://localhost:8080") {
        if ((Call "GET" "$candidate/actuator/health").Status -eq 200) { $Gateway = $candidate; break }
    }
    if (-not $Gateway) { $Gateway = "http://localhost:8081" }
}
Info "using gateway $Gateway"
$services = @(
    @{ n = "config-service";      u = "http://localhost:8888/actuator/health" },
    @{ n = "merchant-service";    u = "http://localhost:9010/actuator/health" },
    @{ n = "payment-service";     u = "http://localhost:9020/actuator/health" },
    @{ n = "operations-service";  u = "http://localhost:9030/actuator/health" },
    @{ n = "vault-service";       u = "http://localhost:9040/actuator/health" },
    @{ n = "api-gateway-service"; u = "$Gateway/actuator/health" }
)
foreach ($s in $services) {
    $r = Call "GET" $s.u
    Check "$($s.n) is UP" ($r.Status -eq 200 -and $r.Body.status -eq "UP") "(HTTP $($r.Status))"
}
$eu = Call "GET" "http://localhost:8761/eureka/apps" @{ Accept = "application/json" }
if ($eu.Status -eq 200) {
    $apps = @($eu.Body.applications.application | ForEach-Object { $_.name })
    Check "Eureka has >= 5 registered apps" ($apps.Count -ge 5) "(found: $($apps -join ', '))"
} else { Check "Eureka reachable" $false "(HTTP $($eu.Status))" }

$colType = Db "pay-payment-db" "select data_type from information_schema.columns where table_name='payment' and column_name='method'"
Check "payment.method column is text (not a number)" ($colType -match "character") "(is '$colType'. Old schema? run: docker compose down -v, then restart everything)"

# ------------------------------------------------------------------ 3. merchants & auth
Section "3) Signup, login, authentication"
$password = "Passw0rd!123"
function New-Merchant($tag) {
    $email = "verify.$tag.$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())@example.com"
    $su = Call "POST" "$Gateway/v1/auth/signup" @{} @{ name = "Verify $tag"; email = $email; password = $password; businessName = "Verify Store $tag"; businessType = "PROPRIETORSHIP" }
    $lg = Call "POST" "$Gateway/v1/auth/login" @{} @{ email = $email; password = $password }
    $token = $lg.Body.accessToken
    $jwt = @{ Authorization = "Bearer $token" }
    $kr = Call "POST" "$Gateway/v1/merchants/api-keys" $jwt @{ environment = "TEST" }
    return @{ Email = $email; SignupStatus = $su.Status; MerchantId = $su.Body.id; LoginStatus = $lg.Status; Token = $token; Jwt = $jwt
              KeyStatus = $kr.Status; KeyUuid = $kr.Body.id; KeyId = $kr.Body.keyId; KeySecret = $kr.Body.keySecret; Auth = (Basic $kr.Body.keyId $kr.Body.keySecret) }
}
$A = New-Merchant "A"
$B = New-Merchant "B"
Check "Signup returns 201" ($A.SignupStatus -eq 201) "(HTTP $($A.SignupStatus))"
Check "Login returns a JWT" ($A.LoginStatus -eq 200 -and $A.Token) "(HTTP $($A.LoginStatus))"
Check "API key created (201)" ($A.KeyStatus -eq 201 -and $A.KeyId -and $A.KeySecret) "(HTTP $($A.KeyStatus))"

$r = Call "POST" "$Gateway/v1/auth/signup" @{} @{ name = "Dup"; email = $A.Email.ToUpper(); password = $password; businessName = "x"; businessType = "PROPRIETORSHIP" }
Check "Duplicate signup (different letter case) is rejected with 409" ($r.Status -eq 409) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/auth/signup" @{} @{ name = "Weak"; email = "weak$(Get-Random)@example.com"; password = "short"; businessName = "x"; businessType = "PROPRIETORSHIP" }
Check "Short password is rejected with 400" ($r.Status -eq 400) "(HTTP $($r.Status))"

$wrongPw = Call "POST" "$Gateway/v1/auth/login" @{} @{ email = $A.Email; password = "WrongPassword!1" }
$noUser  = Call "POST" "$Gateway/v1/auth/login" @{} @{ email = "nobody$(Get-Random)@example.com"; password = "WrongPassword!1" }
Check "Wrong password returns 401" ($wrongPw.Status -eq 401) "(HTTP $($wrongPw.Status))"
Check "Unknown email returns the SAME 401 (no user enumeration)" ($noUser.Status -eq 401 -and $noUser.Body.errorDescription -eq $wrongPw.Body.errorDescription) "(HTTP $($noUser.Status))"
$r = Call "POST" "$Gateway/v1/auth/login" @{} @{ email = $A.Email.ToUpper(); password = $password }
Check "Login works with different letter case in the email" ($r.Status -eq 200) "(HTTP $($r.Status))"

$r = Call "GET" "$Gateway/v1/merchants/api-keys"
Check "Protected route without credentials -> 401" ($r.Status -eq 401) "(HTTP $($r.Status))"
$r = Call "GET" "$Gateway/v1/merchants/api-keys" @{ Authorization = "Bearer not.a.jwt" }
Check "Garbage JWT -> 401" ($r.Status -eq 401) "(HTTP $($r.Status))"
$r = Call "GET" "$Gateway/v1/orders/$([guid]::NewGuid())" (Basic "pp_test_nope" "nope")
Check "Unknown API key -> 401" ($r.Status -eq 401) "(HTTP $($r.Status))"

# ------------------------------------------------------------------ 4. api keys
Section "4) API key management"
$r = Call "POST" "$Gateway/v1/merchants/api-keys" $A.Jwt @{}
Check "Create key without 'environment' -> 400 (was a 500)" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "GET" "$Gateway/v1/merchants/api-keys" $A.Jwt
Check "List keys returns the new key" ($r.Status -eq 200 -and (@($r.Body) | Where-Object { $_.keyId -eq $A.KeyId })) "(HTTP $($r.Status))"

$tmp = Call "POST" "$Gateway/v1/merchants/api-keys" $A.Jwt @{ environment = "TEST" }
$tmpAuth = Basic $tmp.Body.keyId $tmp.Body.keySecret
$rot = Call "POST" "$Gateway/v1/merchants/api-keys/$($tmp.Body.id)/rotate" $A.Jwt
Check "Rotate key returns a NEW secret" ($rot.Status -eq 200 -and $rot.Body.keySecret -and $rot.Body.keySecret -ne $tmp.Body.keySecret) "(HTTP $($rot.Status))"
$r = Call "GET" "$Gateway/v1/orders/$([guid]::NewGuid())" (Basic $tmp.Body.keyId $rot.Body.keySecret)
Check "New secret authenticates (404 = authenticated, order just doesn't exist)" ($r.Status -eq 404) "(HTTP $($r.Status))"
$del = Call "DELETE" "$Gateway/v1/merchants/api-keys/$($tmp.Body.id)" $A.Jwt
Check "Revoke key: DELETE /api-keys/{id} -> 204 (route was broken)" ($del.Status -eq 204) "(HTTP $($del.Status))"
$r = Call "GET" "$Gateway/v1/orders/$([guid]::NewGuid())" (Basic $tmp.Body.keyId $rot.Body.keySecret)
Check "Revoked key is rejected -> 401" ($r.Status -eq 401) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/merchants/api-keys/$($tmp.Body.id)/rotate" $A.Jwt
Check "Rotating a revoked key -> 4xx, not 500" ($r.Status -ge 400 -and $r.Status -lt 500) "(HTTP $($r.Status))"
$r = Call "DELETE" "$Gateway/v1/merchants/api-keys/$($A.KeyUuid)" $B.Jwt
Check "Merchant B cannot revoke merchant A's key -> 404" ($r.Status -eq 404) "(HTTP $($r.Status))"

# ------------------------------------------------------------------ 5. orders
Section "5) Orders"
function New-Order($merchant, $amountUnits = 50000, $currency = "INR", $receipt = $null) {
    if (-not $receipt) { $receipt = "rcpt_$([guid]::NewGuid().ToString('N').Substring(0,12))" }
    return Call "POST" "$Gateway/v1/orders" $merchant.Auth @{ amount = @{ amountUnits = $amountUnits; currency = $currency }; receipt = $receipt; customer = @{ name = "Test User"; email = "user@example.com"; phone = "9999999999" } }
}
$r = New-Order $A 0
Check "Zero amount -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = New-Order $A -100
Check "Negative amount -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = New-Order $A 100 "USD"
Check "Unsupported currency -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/orders" $A.Auth @{ receipt = "no-amount" }
Check "Missing amount -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"

$receipt = "rcpt_dup_$(Get-Random)"
$o1 = New-Order $A 50000 "INR" $receipt
Check "Create order (Rs 500) -> 201, status CREATED" ($o1.Status -eq 201 -and $o1.Body.status -eq "CREATED" -and $o1.Body.id) "(HTTP $($o1.Status))"
Check "Order belongs to the authenticated merchant" ($o1.Body.merchantId -eq $A.MerchantId)
$r = New-Order $A 50000 "INR" $receipt
Check "Duplicate receipt -> 409" ($r.Status -eq 409) "(HTTP $($r.Status))"

$r = Call "GET" "$Gateway/v1/orders/$($o1.Body.id)" $A.Auth
Check "GET /v1/orders/{id} -> 200" ($r.Status -eq 200 -and $r.Body.id -eq $o1.Body.id) "(HTTP $($r.Status))"
$r = Call "GET" "$Gateway/v1/orders/$($o1.Body.id)" $B.Auth
Check "Merchant B cannot read merchant A's order -> 404" ($r.Status -eq 404) "(HTTP $($r.Status))"
$r = Call "GET" "$Gateway/v1/orders/$([guid]::NewGuid())" $A.Auth
Check "Unknown order id -> 404 with ORDER_NOT_FOUND" ($r.Status -eq 404 -and $r.Body.errorCode -eq "ORDER_NOT_FOUND") "(HTTP $($r.Status))"
$r = Call "GET" "$Gateway/v1/orders/not-a-uuid" $A.Auth
Check "Malformed order id -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"

# header forgery: A's key + a forged merchant header must still act as A
$forged = $A.Auth + @{ "X-Merchant-Id" = $B.MerchantId; "X-User-Role" = "ADMIN"; "X-Environment" = "LIVE" }
$r = New-Order @{ Auth = $forged }
Check "SECURITY: forged X-Merchant-Id header is ignored (order still belongs to A)" ($r.Status -eq 201 -and $r.Body.merchantId -eq $A.MerchantId) "(HTTP $($r.Status), merchantId=$($r.Body.merchantId))"

# cancel
$oc = New-Order $A
$r = Call "POST" "$Gateway/v1/orders/$($oc.Body.id)/cancel" $A.Auth
Check "Cancel an unpaid order -> 200 CANCELLED" ($r.Status -eq 200 -and $r.Body.status -eq "CANCELLED") "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $oc.Body.id; method = "UPI"; methodDetails = @{ vpa = "test@upi" } }
Check "Paying a cancelled order -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"

# ------------------------------------------------------------------ 6. payments
Section "6) UPI payments, state machine, idempotency"
$r = Call "POST" "$Gateway/v1/payments" $A.Auth @{ method = "UPI" }
Check "Payment without orderId -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $o1.Body.id; method = "BITCOIN" }
Check "Unknown payment method -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $o1.Body.id; method = "CARD" }
Check "CARD payment without token -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $o1.Body.id; method = "NETBANKING" }
Check "NETBANKING payment without bank -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/payments" $B.Auth @{ orderId = $o1.Body.id; method = "UPI"; methodDetails = @{ vpa = "test@upi" } }
Check "Merchant B cannot pay merchant A's order -> 404" ($r.Status -eq 404) "(HTTP $($r.Status))"

# deterministic failure path
$of = New-Order $A
$pf = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $of.Body.id; method = "UPI"; methodDetails = @{ vpa = "fail@okaxis" } }
Check "UPI with the magic failing VPA -> FAILED / UPI_REJECTED" ($pf.Status -eq 201 -and $pf.Body.status -eq "FAILED" -and $pf.Body.errorCode -eq "UPI_REJECTED") "(HTTP $($pf.Status), status=$($pf.Body.status) code=$($pf.Body.errorCode))"

# happy path (the bank simulator approves ~90% of payments, so retry on simulator declines)
$idem = [guid]::NewGuid().ToString()
$captured = $null; $capturedOrder = $null; $attempts = 0
while (-not $captured -and $attempts -lt 8) {
    $attempts++
    $ord = if ($attempts -eq 1) { $o1 } else { New-Order $A }
    $headers = $A.Auth + @{ "X-Idempotency-Key" = $idem }
    if ($attempts -gt 1) { $idem = [guid]::NewGuid().ToString(); $headers = $A.Auth + @{ "X-Idempotency-Key" = $idem } }
    $p1 = Call "POST" "$Gateway/v1/payments" $headers @{ orderId = $ord.Body.id; method = "UPI"; methodDetails = @{ vpa = "test@upi" } }
    if ($attempts -eq 1) {
        Check "UPI payment initiated -> 201 AUTHORIZING" ($p1.Status -eq 201 -and $p1.Body.status -eq "AUTHORIZING" -and $p1.Body.id) "(HTTP $($p1.Status), status=$($p1.Body.status))"
        $p2 = Call "POST" "$Gateway/v1/payments" $headers @{ orderId = $ord.Body.id; method = "UPI"; methodDetails = @{ vpa = "test@upi" } }
        Check "IDEMPOTENCY: same key returns the same payment id" ($p2.Body.id -eq $p1.Body.id) "(first=$($p1.Body.id) second=$($p2.Body.id))"
    }
    if (-not $p1.Body.id) { break }
    $payId = $p1.Body.id
    [void](Wait-Until { $g = Call "GET" "$Gateway/v1/payments/$payId" $A.Auth; $g.Body.status -in @("CAPTURED","FAILED") } 40)
    $final = Call "GET" "$Gateway/v1/payments/$payId" $A.Auth
    if ($attempts -eq 1) { Check "GET /v1/payments/{id} -> 200" ($final.Status -eq 200 -and $final.Body.id -eq $payId) "(HTTP $($final.Status))" }
    if ($final.Body.status -eq "CAPTURED") { $captured = $final.Body; $capturedOrder = $ord }
    else { Info "attempt ${attempts}: bank simulator declined this payment (normal, ~10%) - trying again" }
}
Check "A payment reaches CAPTURED (full authorise + capture flow)" ($captured -ne $null) "(8 attempts all declined?)"
if ($captured) {
    $og = Call "GET" "$Gateway/v1/orders/$($capturedOrder.Body.id)" $A.Auth
    Check "Order moved to PAID after capture" ($og.Body.status -eq "PAID") "(status=$($og.Body.status))"
    $lp = Call "GET" "$Gateway/v1/orders/$($capturedOrder.Body.id)/payments" $A.Auth
    Check "GET /v1/orders/{id}/payments lists the payment" ($lp.Status -eq 200 -and @($lp.Body).Count -ge 1) "(HTTP $($lp.Status))"
    $cap = Call "POST" "$Gateway/v1/payments/$($captured.id)/capture" $A.Auth
    Check "Capturing an already captured payment -> 409" ($cap.Status -eq 409) "(HTTP $($cap.Status))"
    $again = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $capturedOrder.Body.id; method = "UPI"; methodDetails = @{ vpa = "test@upi" } }
    Check "Paying an already PAID order -> 400" ($again.Status -eq 400) "(HTTP $($again.Status))"
    $logCount = Db "pay-payment-db" "select count(*) from payment_transition_log where payment_id='$($captured.id)'"
    Check "State transitions were logged (>= 4)" ([int]($logCount -as [int]) -ge 4) "(count='$logCount')"
}
$r = Call "GET" "$Gateway/v1/payments/$([guid]::NewGuid())" $A.Auth
Check "Unknown payment id -> 404" ($r.Status -eq 404) "(HTTP $($r.Status))"
if ($captured) {
    $r = Call "GET" "$Gateway/v1/payments/$($captured.id)" $B.Auth
    Check "Merchant B cannot read merchant A's payment -> 404" ($r.Status -eq 404) "(HTTP $($r.Status))"
}
$r = Call "PUT" "$Gateway/v1/payments" $A.Auth @{ x = 1 }
Check "Wrong HTTP method -> 405 (was a 500)" ($r.Status -eq 405) "(HTTP $($r.Status))"

# ------------------------------------------------------------------ 7. cards / vault
Section "7) Card tokenisation (vault) and card payments"
$year = (Get-Date).Year + 2
$tok = Call "POST" "$Gateway/v1/vault/tokenize" $A.Auth @{ pan = "4111111111111111"; cvv = "123"; expiryMonth = 12; expiryYear = $year; cardHolderName = "Test User" }
Check "Tokenize a card -> 201 with tok_ token (also proves AES encryption works)" ($tok.Status -eq 201 -and $tok.Body.token -like "tok_*") "(HTTP $($tok.Status))"
Check "Token response never contains the card number" (-not ($tok.Raw -match "4111111111111111"))
$r = Call "POST" "$Gateway/v1/vault/tokenize" $A.Auth @{ pan = "4111111111111112"; cvv = "123"; expiryMonth = 12; expiryYear = $year; cardHolderName = "Test User" }
Check "Card failing the Luhn check -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"
$r = Call "POST" "$Gateway/v1/vault/tokenize" $A.Auth @{ pan = "4111111111111111"; cvv = "123"; expiryMonth = 12; expiryYear = 2001; cardHolderName = "Test User" }
Check "Expired card year -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"

if ($tok.Body.token) {
    $oc1 = New-Order $A
    $cp = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $oc1.Body.id; method = "CARD"; methodDetails = @{ token = $tok.Body.token } }
    # (the first card call is slow, so the bank simulator may already have finished the payment: both are fine)
    Check "CARD payment with a valid token is accepted (201, AUTHORIZING or already CAPTURED)" ($cp.Status -eq 201 -and $cp.Body.status -in @("AUTHORIZING","AUTHORIZED","CAPTURED")) "(HTTP $($cp.Status), status=$($cp.Body.status) code=$($cp.Body.errorCode) why=$($cp.Body.errorDescription))"

    $ob = New-Order $B
    $steal = Call "POST" "$Gateway/v1/payments" $B.Auth @{ orderId = $ob.Body.id; method = "CARD"; methodDetails = @{ token = $tok.Body.token } }
    Check "SECURITY: merchant B cannot charge merchant A's card token" ($steal.Body.status -eq "FAILED" -and $steal.Body.errorCode -eq "CARD_TOKEN_INVALID") "(status=$($steal.Body.status) code=$($steal.Body.errorCode) why=$($steal.Body.errorDescription))"

    $ob2 = New-Order $A
    $bogus = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $ob2.Body.id; method = "CARD"; methodDetails = @{ token = "tok_doesnotexist" } }
    Check "Unknown card token -> FAILED / CARD_TOKEN_INVALID" ($bogus.Body.status -eq "FAILED" -and $bogus.Body.errorCode -eq "CARD_TOKEN_INVALID") "(status=$($bogus.Body.status) code=$($bogus.Body.errorCode) why=$($bogus.Body.errorDescription))"
}

# ------------------------------------------------------------------ 8. webhooks / kafka / outbox
Section "8) Webhooks: outbox -> Kafka -> delivery, with a verifiable signature"
$wh = Call "POST" "$Gateway/v1/merchants/webhooks" $A.Jwt @{ targetUrl = "http://localhost:9030/webhook/success" }
Check "Create webhook config -> 200 with secret (proves webhook-secret encryption works)" ($wh.Status -eq 200 -and $wh.Body.webhookSecret) "(HTTP $($wh.Status))"
$r = Call "GET" "$Gateway/v1/merchants/webhooks" $A.Jwt
Check "Listing webhooks never shows the secret again" ($r.Status -eq 200 -and -not ($r.Raw -match [regex]::Escape("$($wh.Body.webhookSecret)")))
$r = Call "POST" "$Gateway/v1/merchants/webhooks" $A.Jwt @{ targetUrl = "ftp://bad" }
Check "Invalid webhook URL -> 400" ($r.Status -eq 400) "(HTTP $($r.Status))"

if ($wh.Body.webhookSecret) {
    # generate fresh events AFTER the webhook exists
    $ow = New-Order $A
    $pw = Call "POST" "$Gateway/v1/payments" $A.Auth @{ orderId = $ow.Body.id; method = "UPI"; methodDetails = @{ vpa = "test@upi" } }
    $delivered = Wait-Until {
        (Db "pay-operations-db" "select count(*) from webhook_event where merchant_id='$($A.MerchantId)' and status='DELIVERED'") -match '^[1-9]'
    } 60
    Check "A webhook event was DELIVERED to the merchant URL" $delivered "(waited 60s; check operations-service + Kafka)"
    $row = Db "pay-operations-db" "select signature || '|' || coalesce(request_body,'') from webhook_event where merchant_id='$($A.MerchantId)' and status='DELIVERED' order by created_at desc limit 1"
    if ($row -match '^([0-9a-f]{64})\|(.+)$') {
        $sig = $Matches[1]; $body = $Matches[2]
        $hmac = New-Object System.Security.Cryptography.HMACSHA256
        $hmac.Key = [Text.Encoding]::UTF8.GetBytes($wh.Body.webhookSecret)
        $calc = (($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($body)) | ForEach-Object { $_.ToString("x2") }) -join "")
        Check "Webhook signature = HMAC-SHA256(secret, exact body that was sent)" ($calc -eq $sig) "(stored=$sig computed=$calc)"
    } else { Check "Webhook row has a signature and the exact request body" $false "(row='$row')" }
}
Start-Sleep -Seconds 6
$pending = Db "pay-payment-db" "select count(*) from outbox_event where status='PENDING' and created_at < now() - interval '60 seconds'"
Check "Outbox: no payment events stuck in PENDING" ($pending -eq "0") "(stuck=$pending)"
$failedOutbox = Db "pay-payment-db" "select count(*) from outbox_event where status='FAILED'"
Check "Outbox: no payment events FAILED" ($failedOutbox -eq "0") "(failed=$failedOutbox)"
$published = Db "pay-payment-db" "select count(*) from outbox_event where status='PUBLISHED'"
Check "Outbox: events were published to Kafka" ([int]($published -as [int]) -gt 0) "(published=$published)"

# ------------------------------------------------------------------ 9. rate limiting
Section "9) Rate limiting"
if ($SkipRateLimit) { Info "skipped (-SkipRateLimit)" }
else {
    $limited = $false; $n = 0
    while (-not $limited -and $n -lt 60) {
        $n++
        $r = Call "POST" "$Gateway/v1/auth/login" @{} @{ email = "brute$(Get-Random)@example.com"; password = "WrongPassword!1" }
        if ($r.Status -eq 429) { $limited = $true }
    }
    Check "Login brute-force is throttled with HTTP 429" $limited "(no 429 after $n attempts)"
    Info "note: this used your login quota for ~1 minute; wait before logging in again"
}

# ------------------------------------------------------------------ summary
Write-Host "`n=============================================" -ForegroundColor Cyan
Write-Host ("PASSED: {0}    FAILED: {1}" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Yellow" })
if ($script:fail -gt 0) {
    Write-Host "Failed checks:" -ForegroundColor Red
    $script:failed | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host "Tip: look at the service log/window named in the failed check, then re-run." -ForegroundColor Yellow
} else {
    Write-Host "Everything works: the codebase passed the full end-to-end verification." -ForegroundColor Green
}
$script:results | ConvertTo-Json | Out-File "verify-report.json" -Encoding utf8
Write-Host "Report saved to verify-report.json"
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
