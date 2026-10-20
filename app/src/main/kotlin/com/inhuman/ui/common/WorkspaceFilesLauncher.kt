package com.inhuman.ui.common

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract

/**
 * Open the workspace root in the system Files app.
 *
 * The provider is the permissionless way to let the user touch these files from
 * outside the app; the absolute path is useful for adb or a root file manager,
 * but an ordinary file manager cannot traverse `/data/user/0`.
 *
 * `root` is the provider's root document id. The authority is the application
 * id plus `.documents`, as declared in `core/data`'s manifest. A device without
 * a DocumentsUI activity returns false rather than crashing the caller.
 */
fun openWorkspaceInFiles(context: Context): Boolean {
	val authority = "${context.packageName}.documents"
	val uri = DocumentsContract.buildRootUri(authority, "root")
	val intent = Intent(Intent.ACTION_VIEW)
		.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
	return runCatching {
		context.startActivity(intent)
		true
	}.getOrDefault(false)
}
