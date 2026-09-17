package id.ocbc.chatty.companion

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Which failures are the connection's fault, and therefore worth telling the customer to retry.
 *
 * Mirrors the walk in `CompanionViewModel.isNetwork`. Kept as its own copy of the rule rather than
 * reaching into the view model, because the view model needs Hilt, a session and a device to build —
 * and the thing worth pinning here is the classification, not the wiring around it.
 */
class NetworkFailureTest {

    private fun isNetwork(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        repeat(16) {
            if (cause == null) return false
            if (cause is IOException) return true
            cause = cause?.cause
        }
        return false
    }

    @Test
    fun `transport faults are the network`() {
        assertEquals(true, isNetwork(UnknownHostException("kamartaj.xyz")))
        assertEquals(true, isNetwork(SocketTimeoutException("timeout")))
        assertEquals(true, isNetwork(IOException("socket closed")))
    }

    @Test
    fun `a wrapped transport fault is still the network`() {
        // Coroutine and flow machinery rethrow with the original as the cause, so the top frame is
        // not the one that knows what happened.
        val wrapped = IllegalStateException("turn failed", IOException("connection reset"))
        assertEquals(true, isNetwork(wrapped))
    }

    @Test
    fun `a bug is not the network`() {
        // Telling someone to check their connection about one of these sends them to fix something
        // that is not broken.
        assertEquals(false, isNetwork(IllegalStateException("no session")))
        assertEquals(false, isNetwork(NullPointerException()))
    }

    @Test
    fun `a cycle of causes does not hang the walk`() {
        // Java forbids an exception causing itself, but nothing stops a pair causing each other.
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertEquals(false, isNetwork(first))
    }
}
