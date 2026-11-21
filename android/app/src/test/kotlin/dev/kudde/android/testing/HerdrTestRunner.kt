package dev.kudde.android.testing

import java.lang.reflect.Method
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner

/**
 * The Robolectric runner for this app's tests: the same, except that each test
 * ends with the main thread's due work run ([MainThreadDrain]), so no test can
 * leave Compose waiting on a callback the next test never gets, and that a test
 * running longer than [WATCHDOG_SECONDS] is cut: the watchdog prints the test
 * thread's stack and interrupts it, so a looping test fails with a trace
 * instead of running for an hour.
 */
class KuddeTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    private val watchdogs = ThreadLocal<Thread?>()

    override fun beforeTest(sandbox: org.robolectric.internal.bytecode.Sandbox, method: FrameworkMethod, bootstrappedMethod: Method) {
        val target = Thread.currentThread()
        val name = "${method.declaringClass.simpleName}.${method.name}"
        val dog = Thread {
            try {
                Thread.sleep(WATCHDOG_SECONDS * 1000)
                System.err.println("WATCHDOG: $name ran over $WATCHDOG_SECONDS s; its stack:")
                target.stackTrace.forEach { System.err.println("    at $it") }
                target.interrupt()
            } catch (_: InterruptedException) {
                // the test ended in time
            }
        }
        dog.isDaemon = true
        dog.name = "kudde-test-watchdog"
        dog.start()
        watchdogs.set(dog)
        super.beforeTest(sandbox, method, bootstrappedMethod)
    }

    override fun afterTest(method: FrameworkMethod, bootstrappedMethod: Method) {
        try {
            // Loaded through the sandbox's class loader, so it sees the sandbox's main looper.
            Class.forName(MainThreadDrain::class.java.name, true, bootstrappedMethod.declaringClass.classLoader)
                .getMethod("run")
                .invoke(null)
        } finally {
            watchdogs.get()?.interrupt()
            watchdogs.remove()
            super.afterTest(method, bootstrappedMethod)
        }
    }

    companion object {
        const val WATCHDOG_SECONDS = 60L
    }
}
