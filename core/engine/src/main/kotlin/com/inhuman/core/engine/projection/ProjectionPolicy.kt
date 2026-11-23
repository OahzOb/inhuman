package com.inhuman.core.engine.projection

/**
 * The budgets one request projection is planned against.
 *
 * These are internal strategy constants, not user settings: a conversation's
 * behaviour should not depend on someone finding an advanced screen. Every
 * value is a plain number so the planner stays a pure function that can be
 * unit-tested without a device, a database or a clock.
 *
 * [maxBriefTokens], [maxBatchTokens], [maxAutoFolds] and [maxPrepareFolds] are
 * deliberately not read by [planProjection]: they bound the maintenance loop
 * that folds the batches and generates the brief, which is the caller's job.
 * They live here so one policy object describes the whole strategy instead of
 * half of it living in an app-layer constant.
 */
data class ProjectionPolicy(
	/** Fraction of the usable window above which a fold is planned. */
	val highWater: Double = 0.65,
	/** Fraction of the usable window a fold should leave in the verbatim window. */
	val target: Double = 0.40,
	/** Fraction of the usable window above which the projection is not sendable. */
	val hardLimit: Double = 0.85,
	/** How many complete turns must stay verbatim, at minimum. */
	val minKeepTurns: Int = 2,
	/** Ceiling for the generated brief, in tokens. */
	val maxBriefTokens: Int = 3_000,
	/** Ceiling for the input of one fold request, in tokens. */
	val maxBatchTokens: Int = 32_000,
	/** How many folds one turn-end maintenance pass may run. */
	val maxAutoFolds: Int = 2,
	/** How many folds the pre-send path may run to make room. */
	val maxPrepareFolds: Int = 4,
	/** Window used when the provider does not report one. */
	val assumedWindow: Int = 64_000,
	/** The output reserve never goes below this many tokens. */
	val outputReserveMin: Int = 2_000,
	/** Ratio used before any measured calibration exists. */
	val defaultRatio: Double = 1.15,
	/** Fixed estimate for one attached image. */
	val imageTokens: Int = 1_500,
)
