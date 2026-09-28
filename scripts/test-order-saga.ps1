<#
.SYNOPSIS
    End-to-end test of the order -> inventory saga against the running services.

.DESCRIPTION
    Needs: docker compose up -d, plus order-service (:8080) and inventory-service (:8083) running.
    Every run uses fresh random product IDs, so it can be run repeatedly on the same database.

    Run:  powershell -ExecutionPolicy Bypass -File scripts\test-order-saga.ps1
#>
param(
    [string]$OrderUrl = "http://localhost:8080/api/v1/orders",
    [string]$InventoryUrl = "http://localhost:8083/api/v1/inventory",
    [string]$KafkaContainer = "ecommerce-kafka"
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

function New-Stock([string]$productId, [int]$quantity) {
    Post-Json $InventoryUrl @{ productId = $productId; sku = "SKU-" + $productId.Substring(0, 8); totalQuantity = $quantity }
}

function Get-Stock([string]$productId) { (Invoke-RestMethod "$InventoryUrl/product/$productId").data }

function New-Order([string]$productId, [int]$quantity) {
    Post-Json $OrderUrl @{ productId = $productId; productName = "Saga test product"; quantity = $quantity; price = 100 }
}

function Get-Order([long]$id) { (Invoke-RestMethod "$OrderUrl/$id").data }

# Polls until the order leaves CREATED (the saga is asynchronous) or the timeout passes.
function Wait-OrderSettled([long]$id, [int]$timeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $order = Get-Order $id
        if ($order.status -ne "CREATED") { return $order }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $order
}

# Polls inventory until the check passes or the timeout passes (release is asynchronous too).
function Wait-Stock([string]$productId, [scriptblock]$until, [int]$timeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $stock = Get-Stock $productId
        if (& $until $stock) { return $stock }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $stock
}

function Send-KafkaMessage([string]$topic, [string]$text) {
    $text | docker exec -i $KafkaContainer /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server localhost:9092 --topic $topic | Out-Null
}

function Read-KafkaTopic([string]$topic) {
    docker exec $KafkaContainer /opt/kafka/bin/kafka-console-consumer.sh `
        --bootstrap-server localhost:9092 --topic $topic --from-beginning --timeout-ms 10000 2>$null
}

# ---------------------------------------------------------------------------------------------

Section "1. Order within stock is CONFIRMED and stock is reserved"
$productA = [guid]::NewGuid().ToString()
New-Stock $productA 10 | Out-Null
$order1 = New-Order $productA 3
Check "order starts as CREATED" ($order1.status -eq "CREATED") "got $($order1.status)"
$order1 = Wait-OrderSettled $order1.id
Check "order becomes CONFIRMED" ($order1.status -eq "CONFIRMED") "got $($order1.status)"
$stock = Get-Stock $productA
Check "3 reserved, 7 available" ($stock.reservedQuantity -eq 3 -and $stock.availableQuantity -eq 7) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"

Section "2. Order above available stock is REJECTED with a reason"
$order2 = Wait-OrderSettled (New-Order $productA 20).id
Check "order becomes REJECTED" ($order2.status -eq "REJECTED") "got $($order2.status)"
Check "reason explains the shortage" ($order2.statusReason -like "*requested 20, available 7*") "got '$($order2.statusReason)'"
$stock = Get-Stock $productA
Check "stock unchanged (3 reserved, 7 available)" ($stock.reservedQuantity -eq 3 -and $stock.availableQuantity -eq 7) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"

Section "3. Order for a product with no inventory is REJECTED"
$order3 = Wait-OrderSettled (New-Order ([guid]::NewGuid().ToString()) 1).id
Check "order becomes REJECTED" ($order3.status -eq "REJECTED") "got $($order3.status)"
Check "reason says no inventory" ($order3.statusReason -like "No inventory found*") "got '$($order3.statusReason)'"

Section "4. Cancelling a confirmed order releases its stock"
Invoke-RestMethod -Method Delete -Uri "$OrderUrl/$($order1.id)" | Out-Null
Check "order is CANCELLED" ((Get-Order $order1.id).status -eq "CANCELLED")
$stock = Wait-Stock $productA { param($s) $s.reservedQuantity -eq 0 }
Check "0 reserved, 10 available" ($stock.reservedQuantity -eq 0 -and $stock.availableQuantity -eq 10) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"

Section "5. Reserving the last unit marks the product OUT_OF_STOCK"
$order5 = Wait-OrderSettled (New-Order $productA 10).id
Check "order for all 10 units is CONFIRMED" ($order5.status -eq "CONFIRMED") "got $($order5.status)"
$stock = Get-Stock $productA
Check "product is OUT_OF_STOCK with 0 available" ($stock.status -eq "OUT_OF_STOCK" -and $stock.availableQuantity -eq 0) `
    "status=$($stock.status) available=$($stock.availableQuantity)"
$order5b = Wait-OrderSettled (New-Order $productA 1).id
Check "next order is REJECTED" ($order5b.status -eq "REJECTED") "got $($order5b.status)"

Section "6. A duplicate OrderCreatedEvent does not reserve stock twice"
$duplicate = @{ eventId = "duplicate-" + [guid]::NewGuid(); orderId = $order5.id; productId = $productA; quantity = 10 } |
    ConvertTo-Json -Compress
Send-KafkaMessage "order.created.v1" $duplicate
Start-Sleep -Seconds 5
$stock = Get-Stock $productA
Check "still exactly 10 reserved" ($stock.reservedQuantity -eq 10 -and $stock.availableQuantity -eq 0) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"
Check "order is still CONFIRMED" ((Get-Order $order5.id).status -eq "CONFIRMED")

Section "7. A cancellation that overtakes its creation event reserves nothing"
$productB = [guid]::NewGuid().ToString()
New-Stock $productB 5 | Out-Null
$ghostOrderId = Get-Random -Minimum 900000000 -Maximum 999999999
Send-KafkaMessage "order.cancelled.v1" (@{ eventId = "early-cancel-" + [guid]::NewGuid(); orderId = $ghostOrderId; productId = $productB } | ConvertTo-Json -Compress)
Start-Sleep -Seconds 3
Send-KafkaMessage "order.created.v1" (@{ eventId = "late-create-" + [guid]::NewGuid(); orderId = $ghostOrderId; productId = $productB; quantity = 2 } | ConvertTo-Json -Compress)
Start-Sleep -Seconds 5
$stock = Get-Stock $productB
Check "stock untouched (0 reserved, 5 available)" ($stock.reservedQuantity -eq 0 -and $stock.availableQuantity -eq 5) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"

Section "8. An unreadable message goes to the dead-letter topic"
$poison = "not-json-" + [guid]::NewGuid()
Send-KafkaMessage "order.created.v1" $poison
Start-Sleep -Seconds 5
$dlt = Read-KafkaTopic "order.created.v1.DLT"
Check "message found on order.created.v1.DLT" (($dlt | Out-String) -like "*$poison*")
$order8 = Wait-OrderSettled (New-Order $productB 1).id
Check "consumer keeps working afterwards (next order CONFIRMED)" ($order8.status -eq "CONFIRMED") "got $($order8.status)"

Section "9. Creating an order without a productId is rejected by validation"
$status = 0
try {
    Post-Json $OrderUrl @{ productName = "No product id"; quantity = 1; price = 100 } | Out-Null
} catch {
    $status = [int]$_.Exception.Response.StatusCode
}
Check "API returns 400" ($status -eq 400) "got $status"

# ---------------------------------------------------------------------------------------------

Write-Host ""
if ($script:failed -eq 0) {
    Write-Host "All $($script:passed) checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:failed) of $($script:passed + $script:failed) checks FAILED." -ForegroundColor Red
    exit 1
}
