package com.inhuman.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The desktop UI language.
 *
 * A code rather than an enum so the stored value is stable if a language is
 * added later. English is the fallback; every call site supplies both strings
 * with [s] instead of a key table, which keeps the two translations next to
 * each other and makes a missing one a compile error rather than a blank label.
 */
val LocalDesktopLanguage = staticCompositionLocalOf { "en" }

/** Called by the language switch in Settings. */
val LocalSetDesktopLanguage = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun s(english: String, chinese: String): String =
	if (LocalDesktopLanguage.current == "zh") chinese else english

fun languageName(code: String): String = when (code) {
	"zh" -> "中文"
	else -> "EN"
}

/**
 * Store/UI errors are English at the source; translate the ones the desktop
 * itself produces. Provider and tool errors are left verbatim because they
 * carry diagnostic detail that a translation would obscure.
 */
@Composable
fun localizedError(raw: String): String {
	if (LocalDesktopLanguage.current != "zh") return raw
	return when {
		raw == "Conversation not found." -> "会话不存在。"
		raw == "There is no user turn to rewind." -> "没有可回退的 user 回合。"
		raw == "This turn has no start checkpoint, so it cannot be restored." ->
			"该回合没有开始 checkpoint，无法恢复。"
		raw == "The workspace is not available." -> "工作区不可用。"
		raw == "The workspace could not be restored; nothing was deleted." ->
			"工作区恢复失败；未删除任何内容。"
		raw == "That user turn is no longer in this conversation." ->
			"该 user 回合已不在本会话中。"
		raw == "There is nothing to compact." -> "没有可压缩的内容。"
		raw == "There is no older prefix to summarize." -> "没有可摘要的旧前缀。"
		raw == "The summarizer returned nothing." -> "摘要模型返回为空。"
		raw == "The conversation no longer exists." -> "会话已不存在。"
		raw.startsWith("Keep at least ") -> "至少保留 2 轮；更早的回合还不够。"
		raw.startsWith("Summarizing failed: ") ->
			"摘要失败：${raw.removePrefix("Summarizing failed: ")}"
		raw.startsWith("Workspace could not be opened: ") ->
			"无法打开工作区：${raw.removePrefix("Workspace could not be opened: ")}"
		raw.startsWith("Could not attach ") -> raw
		else -> raw
	}
}

/**
 * Status lines are assembled in event callbacks, where a composable lookup is
 * not available. Translate at the point they are drawn instead.
 */
@Composable
fun localizedStatus(raw: String): String {
	if (LocalDesktopLanguage.current != "zh") return raw
	return when {
		raw == "Saved." -> "已保存。"
		raw == "Deleted." -> "已删除。"
		raw == "Key removed; replies use the demo provider." ->
			"密钥已移除；回复改用 Demo provider。"
		raw == "Key saved." -> "密钥已保存。"
		raw == "Nothing changed." -> "没有变化。"
		raw == "That provider no longer exists." -> "服务商已不存在。"
		raw == "Backup staged. Close and reopen inhuman to apply it." ->
			"备份已暂存；关闭并重新打开 inhuman 后应用。"
		raw == "Model set for this conversation." -> "本会话模型已设置。"
		raw == "SSH disabled; every conversation grant was revoked." ->
			"SSH 已关闭；所有会话授权已撤销。"
		raw == "SSH limits saved." -> "SSH 上限已保存。"
		raw == "Every conversation will ask again." -> "所有会话都会重新询问。"
		raw == "Log cleared." -> "日志已清空。"
		raw == "This file is too large to edit here; use Open or Export." ->
			"文件过大，无法在此编辑；请使用打开或导出。"
		raw == "Open a conversation before browsing its files." ->
			"请先打开一个会话再浏览文件。"
		raw.startsWith("Imported ") -> "已导入 ${raw.removePrefix("Imported ")}"
		raw.startsWith("Import failed: ") -> "导入失败：${raw.removePrefix("Import failed: ")}"
		raw.startsWith("Exported to ") -> "已导出到 ${raw.removePrefix("Exported to ")}"
		raw.startsWith("Export failed: ") -> "导出失败：${raw.removePrefix("Export failed: ")}"
		raw.startsWith("Backup written to ") -> "备份已写入 ${raw.removePrefix("Backup written to ")}"
		raw.startsWith("Backup failed: ") -> "备份失败：${raw.removePrefix("Backup failed: ")}"
		raw.startsWith("Could not read the file: ") ->
			"无法读取文件：${raw.removePrefix("Could not read the file: ")}"
		raw.startsWith("Save failed: ") -> "保存失败：${raw.removePrefix("Save failed: ")}"
		else -> raw
	}
}
