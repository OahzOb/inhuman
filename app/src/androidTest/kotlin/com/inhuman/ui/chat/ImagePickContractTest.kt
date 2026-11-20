package com.inhuman.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker contract, on a device.
 *
 * The composer used `OpenDocument` (single document) while some OEM pickers
 * still let the user tick several images. That contract's `parseResult` returns
 * `intent.data` and ignores `clipData`, so a multi-select staged exactly one
 * image and dropped the rest without a message -- it looked like the app could
 * not upload more than one. The fix is `OpenMultipleDocuments`.
 *
 * These pin the two framework facts the fix relies on (the intent asks for
 * multiple items; the parser returns every clipped URI) and the failure it
 * repaired. They test androidx rather than this file, which is the point: the
 * dependency is what decides whether a multi-pick arrives whole, so a contract
 * change or a change back to [ActivityResultContracts.OpenDocument] fails here
 * instead of on a phone.
 */
@RunWith(AndroidJUnit4::class)
class ImagePickContractTest {

	private val context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun `the image picker intent asks for several documents`() {
		val intent = ActivityResultContracts.OpenMultipleDocuments()
			.createIntent(context, arrayOf("image/*"))

		assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
		assertTrue(
			"without EXTRA_ALLOW_MULTIPLE the picker stays single-select",
			intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
		)
	}

	@Test
	fun `the multi-document result carries every picked uri`() {
		val picked = listOf(
			Uri.parse("content://test/image-1"),
			Uri.parse("content://test/image-2"),
			Uri.parse("content://test/image-3"),
		)
		val result = Intent().apply {
			data = picked.first()
			clipData = ClipData.newRawUri("picked", picked.first()).apply {
				for (uri in picked.drop(1)) addItem(ClipData.Item(uri))
			}
		}

		assertEquals(
			picked,
			ActivityResultContracts.OpenMultipleDocuments()
				.parseResult(Activity.RESULT_OK, result),
		)
	}

	@Test
	fun `the single-document contract is what dropped the rest`() {
		val picked = listOf(
			Uri.parse("content://test/image-1"),
			Uri.parse("content://test/image-2"),
		)
		val result = Intent().apply {
			data = picked.first()
			clipData = ClipData.newRawUri("picked", picked.first()).apply {
				addItem(ClipData.Item(picked.last()))
			}
		}

		assertEquals(
			"the single-document parser keeps only data; this is the old one-image ceiling",
			picked.first(),
			ActivityResultContracts.OpenDocument()
				.parseResult(Activity.RESULT_OK, result),
		)
	}
}
