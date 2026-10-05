package com.inhuman

import android.app.Application

/**
 * Application entry point.
 *
 * Deliberately empty. There is no DI container: the graph is five objects
 * constructed in `InhumanRoot`, and a container would be more configuration
 * than the thing it configures. Objects that must be singular for correctness
 * rather than for tidiness -- `SecureStore`, `ConversationRepository`,
 * `AgentRepository` -- are each built once per process and passed down.
 */
class InhumanApp : Application()
