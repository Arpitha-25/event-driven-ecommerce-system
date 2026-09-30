<#
.SYNOPSIS
    Measures GET /api/v1/products/{id} latency with the Redis cache on and off.

.DESCRIPTION
    Needs the stack running (docker compose up -d) and must be run from the repository root.
    1. measures with the cache on (the default),
    2. restarts product-service with PRODUCT_CACHE_TYPE=none and measures again,
    3. restarts product-service with the cache back on.
    Calls product-service directly (:8082), so gateway overhead doesn't blur the comparison.
    One HTTP connection is reused, and warm-up requests are not counted.

    Run:  powershell -ExecutionPolicy Bypass -File scripts\measure-product-cache.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8082",
    [int]$Warmup = 300,
    [int]$Requests = 2000
)

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient

function Wait-Healthy {
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        try { $ok = (Invoke-WebRequest -UseBasicParsing "$BaseUrl/actuator/health").StatusCode -eq 200 } catch { $ok = $false }
    } while (-not $ok -and (Get-Date) -lt $deadline)
    if (-not $ok) { throw "product-service did not become healthy" }
}

function Restart-ProductService([string]$cacheType) {
    $env:PRODUCT_CACHE_TYPE = $cacheType
    docker compose up -d --no-deps --force-recreate product-service 2>$null | Out-Null
    Remove-Item Env:\PRODUCT_CACHE_TYPE
    Wait-Healthy
}

function Measure-Latency([string]$url, [string]$expectInRedis) {
    for ($i = 0; $i -lt $Warmup; $i++) { $client.GetStringAsync($url).Result | Out-Null }
    # Prove the mode is real: with the cache on, the warm-up stored the product in Redis; with it off, it didn't.
    $inRedis = (docker exec ecommerce-redis redis-cli EXISTS $script:cacheKey).Trim()
    if ($inRedis -ne $expectInRedis) { throw "expected EXISTS=$expectInRedis for $script:cacheKey, got $inRedis" }
    $times = New-Object double[] $Requests
    $timer = New-Object System.Diagnostics.Stopwatch
    for ($i = 0; $i -lt $Requests; $i++) {
        $timer.Restart()
        $response = $client.GetAsync($url).Result
        $response.Content.ReadAsStringAsync().Result | Out-Null
        $timer.Stop()
        if (-not $response.IsSuccessStatusCode) { throw "HTTP $($response.StatusCode)" }
        $times[$i] = $timer.Elapsed.TotalMilliseconds
    }
    $sorted = $times | Sort-Object
    return [pscustomobject]@{
        Median = [math]::Round($sorted[[int]($Requests * 0.50)], 2)
        P95    = [math]::Round($sorted[[int]($Requests * 0.95)], 2)
        P99    = [math]::Round($sorted[[int]($Requests * 0.99)], 2)
        Mean   = [math]::Round(($times | Measure-Object -Average).Average, 2)
        PerSec = [math]::Round(1000 / ($times | Measure-Object -Average).Average)
    }
}

# A product to read.
$sku = "LATENCY-" + [guid]::NewGuid()
$body = @{ sku = $sku; name = "Latency test $sku"; description = "for measure-product-cache.ps1"; price = 10; category = "BOOKS" } | ConvertTo-Json
$id = (Invoke-RestMethod -Method Post "$BaseUrl/api/v1/products" -ContentType "application/json" -Body $body).data.id
$url = "$BaseUrl/api/v1/products/$id"
$script:cacheKey = "product-service:products::$id"
Write-Host "Product $id, $Warmup warm-up + $Requests measured requests per run`n"

Write-Host "Cache ON  (Redis) ..."
Restart-ProductService "redis"
$on = Measure-Latency $url "1"

Write-Host "Cache OFF (every read from PostgreSQL) ..."
Restart-ProductService "none"
docker exec ecommerce-redis redis-cli DEL $script:cacheKey | Out-Null
$off = Measure-Latency $url "0"

Write-Host "Restoring the cache ..."
Restart-ProductService "redis"

Write-Host ""
Write-Host ("{0,-28} {1,10} {2,10} {3,10} {4,10} {5,10}" -f "", "median ms", "p95 ms", "p99 ms", "mean ms", "req/s")
Write-Host ("{0,-28} {1,10} {2,10} {3,10} {4,10} {5,10}" -f "Cache OFF (PostgreSQL)", $off.Median, $off.P95, $off.P99, $off.Mean, $off.PerSec)
Write-Host ("{0,-28} {1,10} {2,10} {3,10} {4,10} {5,10}" -f "Cache ON  (Redis)", $on.Median, $on.P95, $on.P99, $on.Mean, $on.PerSec)
Write-Host ""
Write-Host ("Median {0:N1}x faster, p95 {1:N1}x faster with the cache." -f ($off.Median / $on.Median), ($off.P95 / $on.P95))
