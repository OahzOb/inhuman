package com.inhuman.core.ai.provider.qwen

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live probe for the Token Plan client. It is skipped unless the key is in the
 * test process environment; it never prints the key or the signed media URL.
 */
class QwenTokenPlanLiveTest {

	@Test
	fun imageAndDecisionParseThroughTheClient() = runBlocking {
		val key = System.getenv("QWEN_TOKEN_PLAN_CN_API_KEY")
		assumeTrue(!key.isNullOrBlank())
		val client = QwenTokenPlanClient(
			apiKey = key!!,
			chatBaseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
		)

		val image = client.generateImage(
			prompt = "A small orange cat sitting on a windowsill, simple test image",
			size = "1024*1024",
		)
		assertTrue("image bytes", image.bytes.size > 10_000)

		val decision = client.decide(
			state = buildJsonObject { put("content", "The checkout page is down for everyone.") },
			questions = buildJsonObject {
				put(
					"urgent",
					buildJsonObject {
						put("type", "noul")
						put("instructions", "Does this need immediate attention?")
					},
				)
			},
		)
		assertTrue(decision.toString(), decision["answers"] != null)
	}
}
