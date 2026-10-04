package android.util

/**
 * Test-only shadow of `android.util.Log`.
 *
 * Unit tests run against the "mockable" android.jar, whose methods throw
 * `RuntimeException("... not mocked")` unless `unitTests.returnDefaultValues`
 * is enabled. The repair/audit code under test (BookImportRepository) logs
 * liberally, so without a shadow every repair function would abort into its
 * own `catch (t: Throwable)` and the tests would silently assert nothing.
 *
 * This class is only compiled into the unit-test source set (it shadows the
 * framework class on the test runtime classpath) and records all log lines so
 * tests can assert that repairs are observable.
 */
class Log private constructor() {

    companion object {
        private val recorded = mutableListOf<String>()

        fun recordedLines(): List<String> = synchronized(recorded) { recorded.toList() }

        fun clear() = synchronized(recorded) { recorded.clear() }

        private fun record(level: Char, tag: String?, msg: String?) =
            synchronized(recorded) { recorded.add("$level|$tag|${msg.orEmpty()}") }

        @JvmStatic
        fun v(tag: String?, msg: String?): Int {
            record('V', tag, msg); return 0
        }

        @JvmStatic
        fun d(tag: String?, msg: String?): Int {
            record('D', tag, msg); return 0
        }

        @JvmStatic
        fun i(tag: String?, msg: String?): Int {
            record('I', tag, msg); return 0
        }

        @JvmStatic
        fun w(tag: String?, msg: String?): Int {
            record('W', tag, msg); return 0
        }

        @JvmStatic
        fun e(tag: String?, msg: String?): Int {
            record('E', tag, msg); return 0
        }

        @JvmStatic
        fun v(tag: String?, msg: String?, tr: Throwable?): Int {
            record('V', tag, "$msg :: ${tr?.message}"); return 0
        }

        @JvmStatic
        fun d(tag: String?, msg: String?, tr: Throwable?): Int {
            record('D', tag, "$msg :: ${tr?.message}"); return 0
        }

        @JvmStatic
        fun i(tag: String?, msg: String?, tr: Throwable?): Int {
            record('I', tag, "$msg :: ${tr?.message}"); return 0
        }

        @JvmStatic
        fun w(tag: String?, msg: String?, tr: Throwable?): Int {
            record('W', tag, "$msg :: ${tr?.message}"); return 0
        }

        @JvmStatic
        fun e(tag: String?, msg: String?, tr: Throwable?): Int {
            record('E', tag, "$msg :: ${tr?.message}"); return 0
        }

        @JvmStatic
        fun wtf(tag: String?, msg: String?): Int {
            record('A', tag, msg); return 0
        }

        @JvmStatic
        fun wtf(tag: String?, msg: String?, tr: Throwable?): Int {
            record('A', tag, "$msg :: ${tr?.message}"); return 0
        }

        @JvmStatic
        fun println(priority: Int, tag: String?, msg: String?): Int {
            record('P', tag, msg); return 0
        }

        @JvmStatic
        fun isLoggable(tag: String?, level: Int): Boolean = true

        @JvmStatic
        fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString().orEmpty()
    }
}