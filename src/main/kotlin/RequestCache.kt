package io.github.bugaevc.requestcache

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class RequestCache<in K, V>(private val request: suspend (K) -> V) {

    private sealed class Entry<V> {
        data class Cached<V>(val value: V) : Entry<V>()
        data class Requesting<V>(val deferred: Deferred<V>, val scope: Scope) : Entry<V>()
    }

    private val cache = mutableMapOf<K, Entry<V>>()

    private class ExplicitSetCause(val value: Any?) : RuntimeException()

    /**
     * Eagerly put a key-value pair into the cache.
     *
     * This will cancel any in-flight request for the same key,
     * and immediately return the value to anyone waiting for it.
     * Any further attempts to get the value for this key will
     * succeed immediately, returning the provided value.
     *
     * @return the previously cached value, if any.
     */
    operator fun set(key: K, value: V): V? {
        val oldEntry = synchronized(cache) {
            cache.put(key, Entry.Cached(value))
        }

        return when (oldEntry) {
            null -> null
            is Entry.Cached -> oldEntry.value
            is Entry.Requesting -> {
                val cause = ExplicitSetCause(value)
                oldEntry.scope.cancel("explicit set", cause)
                null
            }
        }
    }

    /**
     * Remove a cached key-value pair for the given key form the cache.
     *
     * This will ensure that any value returned from [getOrRequest] at any later
     * moment will be based on a fetch result (or another value, if explicitly
     * [set] later), not on the value present in the cache before this call.
     *
     * If there's no cached value for this key yet, this method does nothing.
     *
     * @return the previously cached value, if any.
     */
    fun uncache(key: K): V? {
        return synchronized(cache) {
            when (val oldEntry = cache[key]) {
                null -> null
                is Entry.Requesting -> null
                is Entry.Cached -> {
                    cache.remove(key)
                    oldEntry.value
                }
            }
        }
    }

    /**
     * Remove all data from this cache.
     *
     * This is equivalent to calling [uncache] for every cached key.
     */
    fun clear() {
        synchronized(cache) {
            cache.forEach { (key, entry) ->
                if (entry is Entry.Cached) {
                    cache.remove(key)
                }
            }
        }
    }

    private class Scope(parentContext: CoroutineContext) : CoroutineScope {
        override val coroutineContext = parentContext + Job()
        private var refCount = 0

        fun ref() {
            refCount++
        }

        fun unref() {
            refCount--
            if (refCount == 0 && isActive) {
                cancel()
            }
        }
    }

    private suspend fun doRequest(key: K, scopeForReference: Scope): V {
        val value = try {
            // Perform the actual request.
            val value = request(key)
            // If we got cancelled, don't bother persisting the value.
            coroutineContext.ensureActive()
            value
        } catch (t: Throwable) {
            // If we failed, remove ourselves from the cache,
            // but only if nobody else has claimed our spot yet.
            synchronized(cache) {
                val entry = cache[key]
                if (entry is Entry.Requesting && entry.scope === scopeForReference) {
                    cache.remove(key)
                }
            }
            throw t
        }

        synchronized(cache) {
            cache[key] = Entry.Cached(value)
        }
        return value
    }

    /**
     * Get the cached value for the given key, if it is cached,
     * or make a new request and wait for its result, caching it.
     *
     * This function provides a uniform interface to get the value
     * for the given key, whether it is already cached at the time
     * this call is made, gets [set] at a later moment, or has to
     * be requested.
     *
     * If the value has to be requested, and the [request] function
     * throws an exception, this exception will be rethrown by all
     * instances of calls of this function waiting for the value.
     *
     * @return the value for this key.
     */
    suspend fun getOrRequest(key: K): V {
        val entry: Entry.Requesting<V> = synchronized(cache) {
            val entry: Entry<V> = cache.getOrPut(key) {
                val scope = Scope(coroutineContext)
                val deferred = scope.async {
                    doRequest(key, scopeForReference = scope)
                }
                Entry.Requesting(deferred, scope)
            }

            when (entry) {
                is Entry.Cached -> return entry.value
                is Entry.Requesting -> {
                    entry.scope.ref()
                    entry
                }
            }
        }

        try {
            return entry.deferred.await()
        } catch (ex: CancellationException) {
            // One fun case we need to handle explicitly is `CancellationException`.
            // It could be us who got cancelled, or it could be the job we're awaiting.
            if (coroutineContext.isActive) {
                // The job we're awaiting got cancelled. Since we have ref'ed it,
                // it's very likely to have been cancelled because of an explicit
                // `set()`. In that case, we can get the explicitly set value from
                // the exception cause, without touching the cache again and thus
                // avoiding races.
                val cause = ex.cause?.cause
                if (cause is ExplicitSetCause) {
                    @Suppress("UNCHECKED_CAST")
                    return cause.value as V
                }
                // However, it's also possible (if unlikely) that the job cancelled
                // itself for whatever reason. In that case, let's throw an exception.
                throw RuntimeException("Unexpected cancellation")
            } else {
                // It was us who got cancelled! Uh oh.
                // Rethrow the error to propagate cancellation.
                throw ex
            }
        } finally {
            // We synchronize the unref over the cache, even though we're not actually
            // accessing the cache here. This is because we don't want to unref it in
            // between someone else discovering it in the cache and ref'ing it.
            synchronized(cache) {
                entry.scope.unref()
            }
        }
    }
}