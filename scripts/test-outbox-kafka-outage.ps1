<#
.SYNOPSIS
    Proves the Transactional Outbox: orders placed while Kafka is down are not lost.

.DESCRIPTION
    Needs: docker compose up -d, plus order-service (:8080) and inventory-service (:8083) running.
    WARNING: stops the Kafka container for about a minute. It is always restarted at the end,
    even if a check fails.

    Run:  powershell -ExecutionPolicy Bypass -File scripts\test-outbox-kafka-outage.ps1
#>
param(
    [string]$OrderUrl = "http://localhost:8080/api/v1/orders",
    [string]$InventoryUrl = "http://localhost:8083/api/v1/inventory",
    [string]$KafkaContainer = "ecommerce-kafka",
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

function Post-Json([string]$url, $body) {
    (Invoke-RestMethod -Method Post -Uri $url -ContentType "application/json" -Body ($body | ConvertTo-Json)).data
}

function Get-Order([long]$id) { (Invoke-RestMethod "$OrderUrl/$id").data }

# Returns "published|attempts|last_error" for the order's ORDER_CREATED outbox row.
function Get-OutboxRow([long]$orderId) {
    $sql = "SELECT (published_at IS NOT NULL) || '|' || attempts || '|' || coalesce(last_error, '') " +
           "FROM outbox_events WHERE aggregate_id = '$orderId' AND event_type = 'ORDER_CREATED';"
    (docker exec $PostgresContainer psql -U postgres -d orderdb -tA -c $sql | Out-String).Trim()
}

$productId = [guid]::NewGuid().ToString()
Post-Json $InventoryUrl @{ productId = $productId; sku = "SKU-" + $productId.Substring(0, 8); totalQuantity = 5 } | Out-Null

try {
    Section "1. Stop Kafka"
    docker stop $KafkaContainer | Out-Null
    $running = (docker inspect -f "{{.State.Running}}" $KafkaContainer | Out-String).Trim()
    Check "Kafka container is stopped" ($running -eq "false") "running=$running"

    Section "2. Place an order while Kafka is down"
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $order = Post-Json $OrderUrl @{ productId = $productId; productName = "Outage test"; quantity = 2; price = 100 }
    $timer.Stop()
    Check "API still accepts the order" ($order.id -gt 0 -and $order.status -eq "CREATED") "got $($order | ConvertTo-Json -Compress)"
    Check "API answers quickly, without waiting on Kafka (< 3 s)" ($timer.Elapsed.TotalSeconds -lt 3) "took $([math]::Round($timer.Elapsed.TotalSeconds, 2)) s"

    Section "3. The event waits safely in the outbox"
    Start-Sleep -Seconds 12
    $row = Get-OutboxRow $order.id
    $parts = $row.Split("|", 3)
    Check "outbox row exists and is NOT published" ($parts[0] -eq "false") "row='$row'"
    Check "relay has tried and recorded why it failed" ([int]$parts[1] -ge 1 -and $parts[2].Length -gt 0) "row='$row'"
    Check "order is still CREATED" ((Get-Order $order.id).status -eq "CREATED")
}
finally {
    Section "4. Start Kafka again"
    docker start $KafkaContainer | Out-Null
}

$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 2
    $status = (Get-Order $order.id).status
} while ($status -eq "CREATED" -and (Get-Date) -lt $deadline)

Check "order becomes CONFIRMED once Kafka is back" ($status -eq "CONFIRMED") "got $status"
$row = Get-OutboxRow $order.id
Check "outbox row is now marked published" ($row.StartsWith("true|")) "row='$row'"
$stock = (Invoke-RestMethod "$InventoryUrl/product/$productId").data
Check "inventory reserved exactly 2 units" ($stock.reservedQuantity -eq 2) "reserved=$($stock.reservedQuantity)"

Write-Host ""
if ($script:failed -eq 0) {
    Write-Host "All $($script:passed) checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:failed) of $($script:passed + $script:failed) checks FAILED." -ForegroundColor Red
    exit 1
}
