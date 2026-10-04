package com.inhuman.core.common

/**
 * Marks code that is a placeholder for a decision not yet made.
 *
 * Used instead of TODO() where the surrounding code must already compile and
 * run -- a TODO here would crash the app the first time the path is exercised,
 * which is worse than a visible marker.
 */
@RequiresOptIn(
	message = "This is a phase-0 placeholder; the real implementation is not written yet.",
	level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Placeholder
