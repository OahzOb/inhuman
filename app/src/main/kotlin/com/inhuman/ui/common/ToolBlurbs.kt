package com.inhuman.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.inhuman.R
import com.inhuman.core.ai.tools.ToolCatalog

/**
 * The human-facing sentence for a catalog entry, in the current language.
 *
 * `ToolCatalog.Entry.blurb` is English because the catalogue lives in `:core:ai`,
 * which has no Android resources. The app has three pickers (assistant, settings,
 * conversation) and they must not drift, so the mapping lives here and every
 * caller uses it. A future entry falls back to the English blurb rather than
 * showing a blank row.
 */
@Composable
fun toolBlurb(entry: ToolCatalog.Entry): String = when (entry.name) {
	"list_files" -> stringResource(R.string.tool_blurb_list_files)
	"read_file" -> stringResource(R.string.tool_blurb_read_file)
	"file_info" -> stringResource(R.string.tool_blurb_file_info)
	"read_time" -> stringResource(R.string.tool_blurb_read_time)
	"web_search" -> stringResource(R.string.tool_blurb_web_search)
	"web_fetch" -> stringResource(R.string.tool_blurb_web_fetch)
	"ssh_list" -> stringResource(R.string.tool_blurb_ssh_list)
	"ssh_read_file" -> stringResource(R.string.tool_blurb_ssh_read_file)
	"ssh_probe" -> stringResource(R.string.tool_blurb_ssh_probe)
	"ssh_write_file" -> stringResource(R.string.tool_blurb_ssh_write_file)
	"ssh_exec" -> stringResource(R.string.tool_blurb_ssh_exec)
	"ssh_upload" -> stringResource(R.string.tool_blurb_ssh_upload)
	"ssh_download" -> stringResource(R.string.tool_blurb_ssh_download)
	"write_file" -> stringResource(R.string.tool_blurb_write_file)
	"append_file" -> stringResource(R.string.tool_blurb_append_file)
	"create_directory" -> stringResource(R.string.tool_blurb_create_directory)
	"move_file" -> stringResource(R.string.tool_blurb_move_file)
	"delete_file" -> stringResource(R.string.tool_blurb_delete_file)
	else -> entry.blurb
}
