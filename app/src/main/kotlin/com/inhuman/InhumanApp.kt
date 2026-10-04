package com.inhuman

import android.app.Application

/**
 * Application entry point.
 *
 * Deliberately empty of DI for now: the Koin graph is introduced in the phase
 * that first has something to inject. Starting a container here with no modules
 * would only be ceremony.
 */
class InhumanApp : Application()
