package com.inhuman

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual service/notification map with two simultaneous turns.
 *
 * Timing two demo streams through the UI was flaky -- a fast demo reply often
 * finished before the second conversation opened -- so this drives the same
 * `TurnNotifications.update` path the view models use and inspects the posted
 * notifications. It covers the old singleton bug: two conversations must
 * produce a summary plus two children, and finishing one must leave the other.
 */
@RunWith(AndroidJUnit4::class)
class TurnNotificationsTest {

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext
	private val manager get() = context.getSystemService(NotificationManager::class.java)

	private fun grantNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			runCatching {
				instrumentation.uiAutomation.grantRuntimePermission(
					context.packageName,
					Manifest.permission.POST_NOTIFICATIONS,
				)
			}
		}
	}

	private fun status(id: String, title: String, awaiting: Boolean = false) = TurnStatus(
		conversationId = id,
		title = title,
		phase = if (awaiting) "Waiting for approval" else "Thinking",
		startedAt = System.currentTimeMillis(),
		streaming = true,
		awaitingApproval = awaiting,
	)

	private fun activeIds(): Set<Int> =
		manager.activeNotifications.mapTo(mutableSetOf()) { it.id }

	private fun await(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
		val deadline = SystemClock.elapsedRealtime() + timeoutMs
		while (SystemClock.elapsedRealtime() < deadline) {
			if (condition()) return true
			SystemClock.sleep(50)
		}
		return condition()
	}

	private fun finish(id: String, title: String) {
		TurnNotifications.update(
			context,
			status(id, title).copy(streaming = false, phase = "Finished"),
		)
	}

	@Test
	fun twoTurnsProduceASummaryAndTwoChildren() {
		grantNotificationPermission()
		val a = "test-notification-a"
		val b = "test-notification-b"
		val idA = TurnNotifications.childId(a)
		val idB = TurnNotifications.childId(b)
		try {
			TurnNotifications.update(context, status(a, "Wire A"))
			TurnNotifications.update(context, status(b, "Wire B"))

			assertTrue(
				"summary notification was not posted",
				await { TurnNotifications.SUMMARY_ID in activeIds() },
			)
			assertTrue(
				"child A was not posted",
				await { idA in activeIds() },
			)
			assertTrue(
				"child B was not posted",
				await { idB in activeIds() },
			)

			val summary = manager.activeNotifications
				.firstOrNull { it.id == TurnNotifications.SUMMARY_ID }
				?.notification
			assertNotNull(summary)
			assertEquals(
				"2 replies running",
				summary?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
			)

			// Finishing one turn must demote the summary to the surviving child.
			finish(a, "Wire A")
			assertTrue(
				"summary survived after one turn finished",
				await { TurnNotifications.SUMMARY_ID !in activeIds() },
			)
			assertTrue("child B disappeared with child A", idB in activeIds())

			finish(b, "Wire B")
			assertTrue(
				"the surviving child was not removed",
				await { idB !in activeIds() },
			)
		} finally {
			TurnNotifications.update(
				context,
				status(a, "Wire A").copy(streaming = false),
			)
			TurnNotifications.update(
				context,
				status(b, "Wire B").copy(streaming = false),
			)
		}
	}

	@Test
	fun awaitingApprovalAddsAReviewActionWithoutApproving() {
		grantNotificationPermission()
		val id = "test-notification-review"
		try {
			TurnNotifications.update(context, status(id, "Wire Review", awaiting = true))
			val notificationId = TurnNotifications.childId(id)
			assertTrue(
				"review notification was not posted",
				await { notificationId in activeIds() },
			)
			val notification = manager.activeNotifications
				.firstOrNull { it.id == notificationId }
				?.notification
			val actions = notification?.actions?.map { it.title.toString() }.orEmpty()
			assertTrue("Review action missing: $actions", "Review" in actions)
			assertTrue("Stop action missing: $actions", "Stop" in actions)
			assertTrue(
				"an approval action must not be one-tap from the notification",
				"Approve" !in actions && "Deny" !in actions,
			)
		} finally {
			TurnNotifications.update(
				context,
				status(id, "Wire Review").copy(streaming = false),
			)
		}
	}
}
