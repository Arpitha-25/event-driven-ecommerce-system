<#
.SYNOPSIS
    End-to-end test of the API gateway and JWT authentication against the running system.

.DESCRIPTION
    Needs the whole stack running: docker compose up -d --build
    Everything goes through the gateway on :8000, as a real client would.
    Uses the development admin account from docker-compose.yml.

    Run:  powershell -ExecutionPolicy Bypass -File scripts\test-gateway-auth.ps1
#>
param(
    [string]$Gateway = "http://localhost:8000",
    [string]$AdminEmail = "admin@ecommerce.local",
    [string]$AdminPassword = "admin12345",
    [string]$PostgresContainer = "ecommerce-postgres"
)

$script:passed = 0
$script:failed = 0

function Check([string]$name, [bool]$condition, [string]$detail = "") {
    if ($condition) {
        Write-Host "  PASS  $name" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "  FAIL  $name  $detail" -ForegroundColor Red
        $script:failed++
    }
}

function Section([string]$title) { Write-Host "`n$title" -ForegroundColor Cyan }

# Calls the gateway and returns @{ Status; Body; Headers } without throwing on 4xx/5xx.
function Call([string]$method, [string]$path, [string]$token = $null, $body = $null, [hashtable]$headers = @{}) {
    $request = [System.Net.HttpWebRequest]::Create("$Gateway$path")
    $request.Method = $method
    $request.ContentType = "application/json"
    if ($token) { $request.Headers.Add("Authorization", "Bearer $token") }
    foreach ($h in $headers.Keys) { $request.Headers.Add($h, $headers[$h]) }
    if ($body -ne $null) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream(); $stream.Write($bytes, 0, $bytes.Length); $stream.Close()
    } elseif ($method -ne "GET") {
        $request.ContentLength = 0
    }
    try { $response = $request.GetResponse() } catch [System.Net.WebException] { $response = $_.Exception.Response }
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
    $text = $reader.ReadToEnd(); $reader.Close()
    $json = $null
    if ($text) { try { $json = $text | ConvertFrom-Json } catch { } }
    $result = @{ Status = [int]$response.StatusCode; Body = $json; Headers = $response.Headers }
    $response.Close()
    return $result
}

function Login([string]$email, [string]$password) {
    (Call "POST" "/api/v1/auth/login" $null @{ email = $email; password = $password }).Body.data.accessToken
}

# ---------------------------------------------------------------------------------------------

Section "1. Accounts and tokens"
$email = "customer-" + [guid]::NewGuid() + "@example.com"
$r = Call "POST" "/api/v1/auth/register" $null @{ email = $email; password = "customer-password-1"; fullName = "Script Customer" }
Check "register returns 201 with role USER" ($r.Status -eq 201 -and $r.Body.data.role -eq "USER") "status=$($r.Status)"
$r = Call "POST" "/api/v1/auth/register" $null @{ email = $email.ToUpper(); password = "another-password-1" }
Check "same email in another case is rejected (409)" ($r.Status -eq 409) "status=$($r.Status)"
$r = Call "POST" "/api/v1/auth/login" $null @{ email = $email; password = "wrong-password" }
Check "wrong password is rejected (401 INVALID_CREDENTIALS)" ($r.Status -eq 401 -and $r.Body.errorCode -eq "INVALID_CREDENTIALS") "status=$($r.Status)"
$customer = Login $email "customer-password-1"
Check "login returns a JWT" ($customer -and $customer.Split('.').Count -eq 3)
$me = Call "GET" "/api/v1/auth/me" $customer
Check "/auth/me identifies the caller" ($me.Status -eq 200 -and $me.Body.data.email -eq $email) "status=$($me.Status)"
$admin = Login $AdminEmail $AdminPassword
Check "the development admin can log in" ($admin -ne $null)

Section "2. Access rules at the gateway"
Check "anyone can browse products" ((Call "GET" "/api/v1/products").Status -eq 200)
Check "orders need a token (401)" ((Call "GET" "/api/v1/orders/1").Status -eq 401)
Check "stock needs a token (401)" ((Call "GET" "/api/v1/inventory").Status -eq 401)
$sku = "SCRIPT-" + [guid]::NewGuid()
$r = Call "POST" "/api/v1/products" $customer @{ sku = $sku; name = "Not allowed"; price = 1; category = "BOOKS" }
Check "a customer cannot create products (403)" ($r.Status -eq 403 -and $r.Body.errorCode -eq "FORBIDDEN") "status=$($r.Status)"
$r = Call "POST" "/api/v1/inventory" $customer @{ productId = [guid]::NewGuid().ToString(); sku = $sku; totalQuantity = 1 }
Check "a customer cannot change stock (403)" ($r.Status -eq 403) "status=$($r.Status)"
Check "an unknown route is denied" ((Call "GET" "/api/v1/admin/anything" $customer).Status -eq 403)

Section "3. Bad tokens are rejected"
$parts = $customer.Split('.')
$payload = $parts[1].Replace('-', '+').Replace('_', '/'); while ($payload.Length % 4) { $payload += '=' }
$claims = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)).Replace('"USER"', '"ADMIN"')
$tamperedPayload = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($claims)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$tampered = $parts[0] + "." + $tamperedPayload + "." + $parts[2]
Check "a token edited to claim ADMIN is rejected (401)" ((Call "POST" "/api/v1/products" $tampered @{ sku = $sku; name = "x"; price = 1; category = "BOOKS" }).Status -eq 401)
Check "garbage instead of a token is rejected (401)" ((Call "GET" "/api/v1/orders/1" "not-a-jwt").Status -eq 401)

Section "4. The order saga through the gateway"
$sku = "SCRIPT-" + [guid]::NewGuid()
# Product names must be unique, so every run uses a new one.
$product = Call "POST" "/api/v1/products" $admin @{ sku = $sku; name = "Gateway script product $sku"; description = "created by the script"; price = 25.5; category = "ELECTRONICS" }
Check "admin creates a product (201)" ($product.Status -eq 201) "status=$($product.Status)"
$productId = $product.Body.data.id
$stock = Call "POST" "/api/v1/inventory" $admin @{ productId = $productId; sku = $sku; totalQuantity = 5 }
Check "admin adds stock (201)" ($stock.Status -eq 201) "status=$($stock.Status)"
$correlationId = "script-" + [guid]::NewGuid()
$order = Call "POST" "/api/v1/orders" $customer @{ productId = $productId; productName = "Gateway script product"; quantity = 2; price = 25.5 } @{ "X-Correlation-ID" = $correlationId }
Check "customer places an order (201, CREATED)" ($order.Status -eq 201 -and $order.Body.data.status -eq "CREATED") "status=$($order.Status)"
Check "the response carries the client's correlation ID" ($order.Headers["X-Correlation-ID"] -eq $correlationId)
$orderId = $order.Body.data.id
$deadline = (Get-Date).AddSeconds(30)
do { Start-Sleep -Milliseconds 500; $status = (Call "GET" "/api/v1/orders/$orderId" $customer).Body.data.status } while ($status -eq "CREATED" -and (Get-Date) -lt $deadline)
Check "the order becomes CONFIRMED" ($status -eq "CONFIRMED") "got $status"
$level = (Call "GET" "/api/v1/inventory/product/$productId" $customer).Body.data
Check "stock shows 2 reserved, 3 available" ($level.reservedQuantity -eq 2 -and $level.availableQuantity -eq 3) "reserved=$($level.reservedQuantity) available=$($level.availableQuantity)"
$sql = "SELECT payload FROM outbox_events WHERE aggregate_id = '$orderId' AND event_type = 'ORDER_CREATED';"
$outbox = (docker exec $PostgresContainer psql -U postgres -d orderdb -tA -c $sql | Out-String)
Check "the correlation ID reached the order's Kafka event" ($outbox -like "*$correlationId*")

# ---------------------------------------------------------------------------------------------

Write-Host ""
if ($script:failed -eq 0) {
    Write-Host "All $($script:passed) checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:failed) of $($script:passed + $script:failed) checks FAILED." -ForegroundColor Red
    exit 1
}
