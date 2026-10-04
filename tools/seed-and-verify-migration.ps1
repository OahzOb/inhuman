# Seed a couple of conversations so the migration has real rows to preserve.
#
# Each SQL statement goes to the device as a single-quoted argument, so nothing
# is re-parsed by PowerShell and no backslash escaping is involved -- which is
# what broke the inline attempt.
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$serial = 'emulator-5556'
$db = 'databases/inhuman.db'

function Sql([string]$statement) {
    $out = & $adb -s $serial shell "run-as com.inhuman sqlite3 $db '$statement'" 2>&1
    return ($out | Out-String).Trim()
}

Write-Host '=== install v4 fresh ==='
& $adb -s $serial install -r 'C:\Projects\inhuman\app\build\outputs\apk\debug\app-debug.apk' 2>&1 |
    Select-Object -Last 1 | ForEach-Object { Write-Host "  $_" }
& $adb -s $serial shell pm clear com.inhuman | Out-Null
& $adb -s $serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5

Write-Host ''
Write-Host '=== seed two conversations and three messages ==='
Sql "INSERT INTO conversations (id,title,workspaceId,agentId,model,reasoningEffort,thinking,createdAt,updatedAt,pinned) VALUES ('c1','keep me','w1','default-agent',NULL,NULL,0,1,1,0);" | Out-Null
Sql "INSERT INTO conversations (id,title,workspaceId,agentId,model,reasoningEffort,thinking,createdAt,updatedAt,pinned) VALUES ('c2','and me','w2',NULL,NULL,NULL,0,1,1,0);" | Out-Null
Sql "INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) VALUES ('m1','c1',NULL,'user','hello',1);" | Out-Null
Sql "INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) VALUES ('m2','c1','m1','assistant','hi there',2);" | Out-Null
Sql "INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) VALUES ('m3','c2',NULL,'user','second',3);" | Out-Null

Write-Host "  user_version : $(Sql 'PRAGMA user_version;')"
Write-Host "  conversations: $(Sql 'SELECT count(*) FROM conversations;')"
Write-Host "  messages     : $(Sql 'SELECT count(*) FROM messages;')"
Write-Host "  agents       : $(Sql 'SELECT count(*) FROM agents;')"

Write-Host ''
Write-Host '=== rewind to a genuine v3 state ==='
Sql 'DROP TABLE IF EXISTS agents;' | Out-Null
Sql 'PRAGMA user_version = 3;' | Out-Null
Write-Host "  user_version : $(Sql 'PRAGMA user_version;')"
Write-Host "  agents table present: $(Sql "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='agents';")"
Write-Host '  (0 means the table is gone, so 3 -> 4 has real work to do)'

Write-Host ''
Write-Host '=== launch, which must run 3 -> 4 ==='
& $adb -s $serial shell am force-stop com.inhuman | Out-Null
Start-Sleep -Seconds 1
& $adb -s $serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 7

$top = (& $adb -s $serial shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>&1) -join ' '
Write-Host "  foreground: $(if ($top -match 'com\.inhuman') { 'com.inhuman — running, no crash' } else { $top.Trim() })"

Write-Host ''
Write-Host '=== did the migration seed the default assistant? ==='
Write-Host "  user_version : $(Sql 'PRAGMA user_version;')"
Write-Host "  conversations: $(Sql 'SELECT count(*) FROM conversations;')   <- must still be 2"
Write-Host "  messages     : $(Sql 'SELECT count(*) FROM messages;')   <- must still be 3"
Write-Host '  agents:'
(Sql 'SELECT id, name, isDefault FROM agents;') -split "`n" | ForEach-Object { Write-Host "    $_" }

Write-Host ''
Write-Host '=== conversations kept their values ==='
(Sql 'SELECT id, title, agentId FROM conversations ORDER BY id;') -split "`n" |
    ForEach-Object { Write-Host "    $_" }
