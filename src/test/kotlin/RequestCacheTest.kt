package io.github.bugaevc.requestcache

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RequestCacheTest {
    private var requestCount = 0
    private var cancelled = false

    @BeforeEach
    fun reset() {
        requestCount = 0
        cancelled = false
    }

    private suspend fun <R> noteCancellation(block: suspend () -> R): R {
        return try {
            block()
        } catch (ex: CancellationException) {
            cancelled = true
            throw ex
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun delayNoCancel(timeMillis: Long) {
        suspendCoroutine<Unit> { continuation ->
            val delay = continuation.context[ContinuationInterceptor] as Delay
            delay.invokeOnTimeout(timeMillis, {
                continuation.resumeWith(Result.success(Unit))
            }, continuation.context)
        }
    }

    private suspend inline fun <reified E: Throwable> assertThrows(
        crossinline block: suspend () -> Unit
    ): E {
        return try {
            block()
            Assertions.fail<Nothing>("Has not thrown ${E::class.qualifiedName}")
        } catch (actualException: Throwable) {
            Assertions.assertTrue(E::class.isInstance(actualException))
            actualException as E
        }
    }

    private val pretendFailure = "Pretend failure"

    private val cache = RequestCache { key: String ->
        requestCount++

        if (key == "block") {
            delayNoCancel(1_000)
        }

        noteCancellation {
            delay(1_000)
        }

        if (key == "throw") {
            throw RuntimeException(pretendFailure)
        }

        "Value for $key"
    }

    @Test
    fun `simple request`() = runBlockingTest {
        val value = cache.getOrRequest("simple")
        Assertions.assertEquals(value, "Value for simple")
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `two sequential identical requests`() = runBlockingTest {
        val key = "sequential"
        val value1 = cache.getOrRequest(key)
        Assertions.assertEquals(requestCount, 1)
        val value2 = cache.getOrRequest(key)
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertSame(value1, value2)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `two parallel identical requests`() = runBlockingTest {
        val key = "parallel"
        val job1 = async {
            cache.getOrRequest(key)
        }
        val job2 = async {
            cache.getOrRequest(key)
        }
        val value1 = job1.await()
        val value2 = job2.await()
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertSame(value1, value2)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `explicit value`() = runBlockingTest {
        val key = "explicit"
        val expected = "Explicitly provided value"
        cache[key] = expected
        val actual = cache.getOrRequest(key)
        Assertions.assertSame(expected, actual)
        Assertions.assertEquals(requestCount, 0)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun uncache() = runBlockingTest {
        val key = "uncache"
        val value1 = cache.getOrRequest(key)
        val previous = cache.uncache(key)
        Assertions.assertSame(value1, previous)
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertFalse(cancelled)

        val value2 = cache.getOrRequest(key)
        Assertions.assertEquals(value1, value2)
        Assertions.assertEquals(requestCount, 2)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `uncache doesn't interrupt requests`() = runBlockingTest {
        val key = "uncache doesn't cancel"
        val job = async {
            cache.getOrRequest(key)
        }
        Assertions.assertEquals(requestCount, 1)
        cache.uncache(key)
        job.await()
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `explicit set cancels request`() = runBlockingTest {
        val key = "explicit set cancels"
        val expected = "Explicitly provided value that should cancel the request"
        val job = async {
            cache.getOrRequest(key)
        }
        cache[key] = expected
        val actual1 = job.await()
        Assertions.assertSame(expected, actual1)
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertTrue(cancelled)

        val actual2 = cache.getOrRequest(key)
        Assertions.assertSame(expected, actual2)
        Assertions.assertEquals(requestCount, 1)
    }

    @Test
    fun `cancelling parent task cancels request`() = runBlockingTest {
        val key = "cancelling parent"
        val job = async {
            cache.getOrRequest(key)
        }
        job.cancelAndJoin()
        Assertions.assertTrue(cancelled)
    }

    private fun CoroutineScope.spawnJobs(key: String, count: Int = 10): List<Deferred<String>> {
        val jobs = mutableListOf<Deferred<String>>()
        for (i in 0 until count) {
            val job = async {
                cache.getOrRequest(key)
            }
            jobs.add(job)
        }
        return jobs
    }

    @Test
    fun `only cancelled when refcount drops to zero`() = runBlockingTest {
        val key = "cancelling refcount"
        val jobs = spawnJobs(key)

        for (job in jobs) {
            Assertions.assertEquals(requestCount, 1)
            Assertions.assertFalse(cancelled)
            job.cancelAndJoin()
        }

        Assertions.assertTrue(cancelled)
    }

    @Test
    fun `request exception propagates`() = runBlockingTest {
        val key = "throw"
        val jobs = spawnJobs(key)

        for (job in jobs) {
            assertThrows<RuntimeException> {
                job.await()
            }.also {
                Assertions.assertEquals(it.message, pretendFailure)
            }
        }

        Assertions.assertEquals(requestCount, 1)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `requesting after failure retries`() = runBlockingTest {
        val key = "throw"

        assertThrows<RuntimeException> {
            cache.getOrRequest(key)
        }.also {
            Assertions.assertEquals(it.message, pretendFailure)
        }
        Assertions.assertEquals(requestCount, 1)
        Assertions.assertFalse(cancelled)

        assertThrows<RuntimeException> {
            cache.getOrRequest(key)
        }.also {
            Assertions.assertEquals(it.message, pretendFailure)
        }
        Assertions.assertEquals(requestCount, 2)
        Assertions.assertFalse(cancelled)
    }

    @Test
    fun `not replacing another request`() = runBlockingTest {
        val key = "block"
        val expected1 = "replaced"

        val job1 = async {
            cache.getOrRequest(key)
        }

        cache[key] = expected1
        cache.uncache(key)

        val job2 = async {
            cache.getOrRequest(key)
        }

        val value1 = job1.await()
        Assertions.assertSame(value1, expected1)
        Assertions.assertEquals(requestCount, 2)

        val value3 = cache.getOrRequest(key)
        val value2 = job2.await()
        Assertions.assertSame(value2, value3)
        Assertions.assertNotEquals(value1, value2)
        Assertions.assertEquals(requestCount, 2)
    }
}