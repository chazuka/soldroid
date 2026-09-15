package id.ocbc.chatty

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The process entry point. Hilt needs it annotated; nothing else belongs here.
 *
 * In particular the LiveKit room is *not* created at startup. It is a native peer-connection factory
 * and an EGL context, and paying for both on a cold launch would delay the first screen — which is a
 * list, and does not need a face.
 */
@HiltAndroidApp
class ChattyApplication : Application()
