package com.inhuman.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The DocumentsProvider URI for one workspace file.
 *
 * Shared by the external viewer and the in-app audio player: both need the
 * same provider-issued URI, and neither may use a private file path.
 */
fun workspaceFileUri(
	context: Context,
	workspaceId: String,
	relativePath: String,
): Uri {
	val authority = "${context.packageName}.documents"
	return DocumentsContract.buildDocumentUri(authority, "$workspaceId/$relativePath")
}

/**
 * Open one workspace file in whatever app handles its MIME type.
 *
 * Generated images, audio and video are ordinary workspace files, so they go
 * through the same DocumentsProvider as the Files screen. A private file path
 * would not be readable by the viewer, and a file:// URI would trip
 * FileUriExposedException.
 */
fun openWorkspaceFile(
	context: Context,
	workspaceId: String,
	relativePath: String,
	mimeType: String,
): Boolean {
	val uri = workspaceFileUri(context, workspaceId, relativePath)
	val intent = Intent(Intent.ACTION_VIEW).apply {
		setDataAndType(uri, mimeType.ifBlank { "*/*" })
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	return runCatching {
		context.startActivity(Intent.createChooser(intent, null))
		true
	}.getOrDefault(false)
}
