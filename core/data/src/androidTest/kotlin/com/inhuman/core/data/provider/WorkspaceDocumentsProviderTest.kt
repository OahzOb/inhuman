package com.inhuman.core.data.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/**
 * A conversation's folder, as the documents provider actually publishes it.
 *
 * The folder's document id is the conversation's `workspaceId`, while the row's
 * primary key is a second UUID. The provider used the wrong one for both its
 * "is this a conversation folder" test and its `queryDocument` lookup, so the
 * failure was silent: deleting or renaming through the folder id succeeded, and
 * a re-queried folder came back named after its raw UUID rather than its title.
 *
 * The DAO distinction is pinned separately by `ConversationLookupTest`; this
 * file is the only test that calls the provider methods with the id the
 * provider itself issues.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceDocumentsProviderTest {

	private lateinit var context: Context
	private lateinit var db: InhumanDatabase
	private lateinit var provider: WorkspaceDocumentsProvider

	private val conversationId = "conversation-provider-test"
	private val workspaceId = "workspace-provider-test"

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		db = Database.open(context)
		provider = WorkspaceDocumentsProvider()
		provider.attachInfo(
			context,
			ProviderInfo().apply {
				authority = "com.inhuman.core.data.test.documents"
				// DocumentsProvider.attachInfo refuses an unexported provider, and
				// one that cannot grant URI permissions: the system has to be able
				// to reach it and hand a subtree to the Files app.
				exported = true
				grantUriPermissions = true
				readPermission = "android.permission.MANAGE_DOCUMENTS"
				writePermission = "android.permission.MANAGE_DOCUMENTS"
			},
		)
		runBlocking {
			db.conversations().delete(conversationId)
			db.workspaces().delete(workspaceId)
			db.workspaces().upsert(
				WorkspaceEntity(
					id = workspaceId,
					name = "Provider test",
					isPermanent = true,
					ownerConversationId = null,
					createdAt = 1L,
					updatedAt = 1L,
				),
			)
			db.conversations().upsert(
				ConversationEntity(
					id = conversationId,
					title = "Provider test",
					workspaceId = workspaceId,
					createdAt = 1L,
					updatedAt = 1L,
				),
			)
		}
		Database.workspaceDir(context, workspaceId).mkdirs()
	}

	@After
	fun tearDown() {
		runBlocking {
			db.conversations().delete(conversationId)
			db.workspaces().delete(workspaceId)
		}
		Database.workspaceDir(context, workspaceId).deleteRecursively()
	}

	@Test
	fun theRootListsWorkspacesRatherThanConversations() {
		provider.queryChildDocuments(
			"root",
			null as Array<out String>?,
			null as String?,
		).use { cursor ->
			var found = false
			while (cursor.moveToNext()) {
				val id = cursor.getString(
					cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
				)
				if (id == workspaceId) {
					found = true
					assertEquals(
						"Provider test",
						cursor.getString(
							cursor.getColumnIndexOrThrow(
								DocumentsContract.Document.COLUMN_DISPLAY_NAME,
							),
						),
					)
				}
			}
			assertTrue("the workspace is missing from the provider root", found)
		}
	}

	@Test
	fun aRequeriedConversationFolderKeepsItsTitle() {
		provider.queryDocument(workspaceId, null).use { cursor ->
			assertTrue(cursor.moveToFirst())
			val name = cursor.getString(
				cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
			)
			assertEquals("Provider test", name)
		}
	}

	@Test
	fun aConversationFolderCannotBeDeletedOrRenamed() {
		try {
			provider.deleteDocument(workspaceId)
			fail("deleteDocument accepted a conversation folder")
		} catch (expected: FileNotFoundException) {
			// The refusal this test exists for.
		}

		try {
			provider.renameDocument(workspaceId, "renamed")
			fail("renameDocument accepted a conversation folder")
		} catch (expected: FileNotFoundException) {
			// Same refusal, same id.
		}

		assertTrue(Database.workspaceDir(context, workspaceId).isDirectory)
	}
}
