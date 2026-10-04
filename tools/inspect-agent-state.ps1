# What is actually stored for the assistant and the conversation?
#
# The picker reported effectiveModel=null while the model list arrived with two
# models and three effort levels each, which points at the stored values rather
# than at the parsing. Reading them directly is faster than another round of
# inference.
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$serial = 'emulator-5556'
$db = 'databases/inhuman.db'

function SqlFile([string]$sql) {
    $p = Join-Path $env:TEMP 'q.sql'
    Set-Content -Path $p -Value $sql -Encoding ASCII
    & $adb -s $serial push $p /data/local/tmp/q.sql 2>&1 | Out-Null
    $out = & $adb -s $serial shell "run-as com.inhuman sh -c 'sqlite3 $db < /data/local/tmp/q.sql'" 2>&1
    & $adb -s $serial shell rm -f /data/local/tmp/q.sql | Out-Null
    return ($out | Out-String).Trim()
}

Write-Host '=== agents ==='
SqlFile @'
.mode line
SELECT id, name, isDefault,
       '[' || COALESCE(model,'NULL') || ']' AS model,
       '[' || COALESCE(reasoningEffort,'NULL') || ']' AS effort,
       thinking
FROM agents;
'@ | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host '=== the newest conversation ==='
SqlFile @'
.mode line
SELECT id, title,
       '[' || COALESCE(agentId,'NULL') || ']' AS agentId,
       '[' || COALESCE(model,'NULL') || ']' AS model,
       '[' || COALESCE(reasoningEffort,'NULL') || ']' AS effort,
       thinking
FROM conversations ORDER BY createdAt DESC LIMIT 1;
'@ | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host '=== how many conversations exist ==='
Write-Host "  $(SqlFile 'SELECT count(*) FROM conversations;')"
