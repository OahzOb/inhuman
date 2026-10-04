# Verify the 3 -> 4 migration against a genuinely v3 database.
#
# ## What the first attempt got wrong
#
# It dropped the `agents` table and set the schema version back to 3, but left
# the `agentId`, `reasoningEffort` and `thinking` columns in place -- those had
# already been added when v4 ran. The migration then failed with "duplicate
# column name: agentId", which is the migration correctly refusing to do
# something twice, and the test reporting its own mistake as a product bug.
#
# A real v3 database has neither the table nor the columns, so the rewind has to
# rebuild `conversations` without them. SQLite's DROP COLUMN would work on modern
# versions but rebuilding is portable and shows exactly what v3 looked like.
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$serial = 'emulator-5556'
$db = 'databases/inhuman.db'

function Sql([string]$statement) {
    $out = & $adb -s $serial shell "run-as com.inhuman sqlite3 $db '$statement'" 2>&1
    return ($out | Out-String).Trim()
}

function SqlFile([string]$sql) {
    # A file rather than an argument: `adb shell` re-parses what it is given, so
    # string literals arrive with their quotes stripped. That silently turned
    # the seeding inserts into no-ops on the first attempt.
    $p = Join-Path $env:TEMP 'mig.sql'
    Set-Content -Path $p -Value $sql -Encoding ASCII
    & $adb -s $serial push $p /data/local/tmp/mig.sql 2>&1 | Out-Null
    $out = & $adb -s $serial shell "run-as com.inhuman sh -c 'sqlite3 $db < /data/local/tmp/mig.sql'" 2>&1
    & $adb -s $serial shell rm -f /data/local/tmp/mig.sql | Out-Null
    return ($out | Out-String).Trim()
}

Write-Host '=== 1. install v4 and seed real data ==='
& $adb -s $serial install -r 'C:\Projects\inhuman\app\build\outputs\apk\debug\app-debug.apk' 2>&1 |
    Select-Object -Last 1 | ForEach-Object { Write-Host "  $_" }
& $adb -s $serial shell pm clear com.inhuman | Out-Null
& $adb -s $serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 6
& $adb -s $serial shell am force-stop com.inhuman | Out-Null

SqlFile @'
INSERT INTO conversations (id,title,workspaceId,agentId,model,reasoningEffort,thinking,createdAt,updatedAt,pinned)
  VALUES ('c1','keep me','w1','default-agent',NULL,NULL,0,1,1,0);
INSERT INTO conversations (id,title,workspaceId,agentId,model,reasoningEffort,thinking,createdAt,updatedAt,pinned)
  VALUES ('c2','and me','w2',NULL,NULL,NULL,0,1,1,0);
INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) VALUES ('m1','c1',NULL,'user','hello',1);
INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) VALUES ('m2','c1','m1','assistant','hi there',2);
INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) VALUES ('m3','c2',NULL,'user','second',3);
SELECT '  seeded conversations=' || count(*) FROM conversations;
SELECT '  seeded messages=' || count(*) FROM messages;
'@ | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '=== 2. rebuild the database as a genuine v3 ==='
# conversations without agentId / reasoningEffort / thinking, and no agents
# table at all -- which is exactly what version 3 looked like.
SqlFile @'
PRAGMA foreign_keys=off;
BEGIN;
CREATE TABLE conversations_v3 (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  workspaceId TEXT NOT NULL,
  model TEXT,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  pinned INTEGER NOT NULL DEFAULT 0
);
INSERT INTO conversations_v3 (id,title,workspaceId,model,createdAt,updatedAt,pinned)
  SELECT id,title,workspaceId,model,createdAt,updatedAt,pinned FROM conversations;
DROP TABLE conversations;
ALTER TABLE conversations_v3 RENAME TO conversations;
CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_workspaceId ON conversations(workspaceId);
DROP TABLE IF EXISTS agents;
PRAGMA user_version = 3;
COMMIT;
PRAGMA foreign_keys=on;
'@ | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '  --- resulting state (this is v3) ---'
SqlFile @'
SELECT '  user_version=' || (SELECT * FROM pragma_user_version);
SELECT '  agents_table=' || count(*) FROM sqlite_master WHERE type='table' AND name='agents';
SELECT '  conversations=' || count(*) FROM conversations;
SELECT '  messages=' || count(*) FROM messages;
SELECT '  has_agentId_column=' || count(*) FROM pragma_table_info('conversations') WHERE name='agentId';
'@ | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '=== 3. launch: the 3 -> 4 migration must run ==='
& $adb -s $serial shell am start -W -n 'com.inhuman/.MainActivity' 2>&1 |
    Select-String 'Status|LaunchState' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
Start-Sleep -Seconds 8
$top = (& $adb -s $serial shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>&1) -join ' '
Write-Host "  foreground: $(if ($top -match 'com\.inhuman') { 'com.inhuman — running, no crash' } else { ($top -split 'topResumedActivity=')[-1].Trim() })"
& $adb -s $serial shell am force-stop com.inhuman | Out-Null

Write-Host ''
Write-Host '=== 4. after the migration ==='
SqlFile @'
SELECT '  user_version=' || (SELECT * FROM pragma_user_version);
SELECT '  conversations=' || count(*) FROM conversations;
SELECT '  messages=' || count(*) FROM messages;
SELECT '  agent: id=' || id || ' name=' || name || ' isDefault=' || isDefault FROM agents;
SELECT '  conv: ' || id || ' | ' || title || ' | agentId=' || COALESCE(agentId,'(none)') || ' | thinking=' || thinking FROM conversations ORDER BY id;
SELECT '  systemPrompt chars=' || length(systemPrompt) FROM agents;
'@ | ForEach-Object { Write-Host $_ }
