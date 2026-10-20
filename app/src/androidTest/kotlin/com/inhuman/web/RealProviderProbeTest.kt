package com.inhuman.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.secret.SecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real provider, exercised only when this device already holds a key.
 *
 * This is deliberately not part of the JVM suite and deliberately not a fake:
 * the failure it catches is a key that the app believes is usable but the
 * provider rejects, or a base URL that has stopped resolving. It makes one
 * `GET /models` call and no completion, so running it costs nothing.
 *
 * On a device without a key it is a no-op rather than a failure or a fake pass.
 * A skipped test is an honest statement that this layer was not exercised; the
 * AndroidJUnitRunner in this tree reports `AssumptionViolatedException` as a
 * failure, so the check is a plain early return instead.
 */
@RunWith(AndroidJUnit4::class)
class RealProviderProbeTest {

	@Test
	fun deepSeekKeyAnswersModels() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val secure = SecureStore(context)
		val agents = AgentRepository(context, secure)
		// No key is not a failure: this probe exists for the device that has one.
		if (!agents.hasKey(ProviderIds.DEEPSEEK)) return
		runBlocking {
			val profile = agents.provider(ProviderIds.DEEPSEEK)
			assertNotNull("the seeded DeepSeek profile is missing", profile)
			val provider = agents.providerFromRow(profile!!)
			assertNotNull("the provider could not be constructed", provider)
			val models = provider!!.listModels()
			assertTrue("the provider returned no models", models.isNotEmpty())
		}
	}
}
