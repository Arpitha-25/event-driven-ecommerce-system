<#
.SYNOPSIS
    End-to-end test of the reliable consumers: processed-event de-duplication, retries with
    exponential backoff, and dead-letter topics.

.DESCRIPTION
    Needs: docker compose up -d, plus order-service (:8080) and inventory-service (:8083) running.
    Duplicates are exact copies of real events, read back from the outbox table and from Kafka.

    Run:  powershell -ExecutionPolicy Bypass -File scripts\test-reliable-consumers.ps1
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
function Get-Stock([string]$productId) { (Invoke-RestMethod "$InventoryUrl/product/$productId").data }

function Wait-OrderStatus([long]$id, [string]$status, [int]$timeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $order = Get-Order $id
        if ($order.status -eq $status) { return $order }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $order
}

function Sql([string]$database, [string]$query) {
    (docker exec $PostgresContainer psql -U postgres -d $database -tA -c $query | Out-String).Trim()
}

function Send-KafkaMessage([string]$topic, [string]$text) {
    $text | docker exec -i $KafkaContainer /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server localhost:9092 --topic $topic | Out-Null
}

function Read-KafkaTopic([string]$topic, [int]$timeoutMs = 8000, [switch]$WithHeaders) {
    $extra = @()
    if ($WithHeaders) { $extra = @("--property", "print.headers=true") }
    docker exec $KafkaContainer /opt/kafka/bin/kafka-console-consumer.sh `
        --bootstrap-server localhost:9092 --topic $topic --from-beginning --timeout-ms $timeoutMs @extra 2>$null
}

function Get-ReplyMessages([string]$topic, [long]$orderId) {
    @(Read-KafkaTopic $topic | Where-Object { $_ -like "*`"orderId`":$orderId,*" })
}

# ---------------------------------------------------------------------------------------------

Section "Setup: a confirmed order"
$productId = [guid]::NewGuid().ToString()
Post-Json $InventoryUrl @{ productId = $productId; sku = "SKU-" + $productId.Substring(0, 8); totalQuantity = 10 } | Out-Null
$order = Wait-OrderStatus (Post-Json $OrderUrl @{ productId = $productId; productName = "Reliability test"; quantity = 3; price = 100 }).id "CONFIRMED"
Check "order is CONFIRMED with 3 units reserved" ($order.status -eq "CONFIRMED" -and (Get-Stock $productId).reservedQuantity -eq 3) `
    "status=$($order.status)"

$createdPayload = Sql "orderdb" "SELECT payload FROM outbox_events WHERE aggregate_id = '$($order.id)' AND event_type = 'ORDER_CREATED';"
$createdEventId = ($createdPayload | ConvertFrom-Json).eventId
Check "found the real OrderCreatedEvent in the outbox" ($createdEventId -ne $null -and $createdEventId.Length -gt 0)

Section "1. Duplicate OrderCreatedEvent (same eventId)"
Send-KafkaMessage "order.created.v1" $createdPayload
Start-Sleep -Seconds 5
$rows = Sql "ecommerce_inventory_db" "SELECT count(*) FROM processed_events WHERE consumer = 'inventory-service:order.created.v1' AND event_id = '$createdEventId';"
Check "inventory recorded the eventId exactly once" ($rows -eq "1") "rows=$rows"
$stock = Get-Stock $productId
Check "stock not reserved twice (still 3 reserved, 7 available)" ($stock.reservedQuantity -eq 3 -and $stock.availableQuantity -eq 7) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"
$replies = Get-ReplyMessages "inventory.reserved.v1" $order.id
Check "inventory re-sent its stored reply (2 replies for this order)" ($replies.Count -eq 2) "replies=$($replies.Count)"
Check "order is still CONFIRMED" ((Get-Order $order.id).status -eq "CONFIRMED")

Section "2. Duplicate InventoryReservedEvent (same eventId)"
$replyJson = $replies[0]
$replyEventId = ($replyJson | ConvertFrom-Json).eventId
Send-KafkaMessage "inventory.reserved.v1" $replyJson
Start-Sleep -Seconds 5
$rows = Sql "orderdb" "SELECT count(*) FROM processed_events WHERE consumer = 'order-service:inventory.reserved.v1' AND event_id = '$replyEventId';"
Check "order-service recorded the eventId exactly once" ($rows -eq "1") "rows=$rows"
Check "order is still CONFIRMED" ((Get-Order $order.id).status -eq "CONFIRMED")

Section "3. Duplicate OrderCancelledEvent (same eventId)"
Invoke-RestMethod -Method Delete -Uri "$OrderUrl/$($order.id)" | Out-Null
$deadline = (Get-Date).AddSeconds(20)
do { Start-Sleep -Milliseconds 500; $stock = Get-Stock $productId } while ($stock.reservedQuantity -ne 0 -and (Get-Date) -lt $deadline)
Check "cancel released the stock (0 reserved, 10 available)" ($stock.reservedQuantity -eq 0 -and $stock.availableQuantity -eq 10) `
    "reserved=$($stock.reservedQuantity)"
$cancelPayload = Sql "orderdb" "SELECT payload FROM outbox_events WHERE aggregate_id = '$($order.id)' AND event_type = 'ORDER_CANCELLED';"
$cancelEventId = ($cancelPayload | ConvertFrom-Json).eventId
Send-KafkaMessage "order.cancelled.v1" $cancelPayload
Start-Sleep -Seconds 5
$rows = Sql "ecommerce_inventory_db" "SELECT count(*) FROM processed_events WHERE consumer = 'inventory-service:order.cancelled.v1' AND event_id = '$cancelEventId';"
Check "inventory recorded the eventId exactly once" ($rows -eq "1") "rows=$rows"
$stock = Get-Stock $productId
Check "stock not released twice (still 0 reserved, 10 available)" ($stock.reservedQuantity -eq 0 -and $stock.availableQuantity -eq 10) `
    "reserved=$($stock.reservedQuantity) available=$($stock.availableQuantity)"

Section "4. A failing event is retried with backoff, then dead-lettered"
$ghostOrderId = Get-Random -Minimum 900000000 -Maximum 999999999
$ghostEventId = "ghost-" + [guid]::NewGuid()
$ghost = @{ eventId = $ghostEventId; correlationId = "reliability-test"; orderId = $ghostOrderId; productId = $productId; quantity = 1 } |
    ConvertTo-Json -Compress
$timer = [System.Diagnostics.Stopwatch]::StartNew()
Send-KafkaMessage "inventory.reserved.v1" $ghost
# Some DLT headers hold binary numbers (partition, offset) that can contain line breaks,
# so the output is searched as one block of text rather than line by line.
$dlt = ""
while ($timer.Elapsed.TotalSeconds -lt 60 -and $dlt -notlike "*$ghostEventId*") {
    # Below ~8 s the console consumer can exit before it has even joined its group.
    $dlt = Read-KafkaTopic "inventory.reserved.v1.DLT" 8000 -WithHeaders | Out-String
}
$timer.Stop()
$seconds = [math]::Round($timer.Elapsed.TotalSeconds, 1)
Check "event reached inventory.reserved.v1.DLT" ($dlt -like "*$ghostEventId*")
Check "only after the retries (1 s + 2 s + 4 s backoff means at least 7 s)" ($seconds -ge 7) "arrived after $seconds s"
Check "DLT headers record the original topic and consumer group" `
    ($dlt -like "*kafka_dlt-original-topic:inventory.reserved.v1*" -and $dlt -like "*kafka_dlt-original-consumer-group:order-group*")
Check "DLT headers record why it failed (OrderNotFoundException for this order)" `
    ($dlt -like "*kafka_dlt-exception-cause-fqcn:com.arpitha.order_service.exception.OrderNotFoundException*" -and
     $dlt -like "*Order not found with ID: $ghostOrderId*")
$rows = Sql "orderdb" "SELECT count(*) FROM processed_events WHERE event_id = '$ghostEventId';"
Check "failed attempts left no processed-event record (rolled back)" ($rows -eq "0") "rows=$rows"
Write-Host "        (dead-lettered after $seconds s)"

Section "5. Consumers keep working afterwards"
$next = Wait-OrderStatus (Post-Json $OrderUrl @{ productId = $productId; productName = "After DLT"; quantity = 1; price = 100 }).id "CONFIRMED"
Check "new order is CONFIRMED" ($next.status -eq "CONFIRMED") "got $($next.status)"

# ---------------------------------------------------------------------------------------------

Write-Host ""
if ($script:failed -eq 0) {
    Write-Host "All $($script:passed) checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:failed) of $($script:passed + $script:failed) checks FAILED." -ForegroundColor Red
    exit 1
}
