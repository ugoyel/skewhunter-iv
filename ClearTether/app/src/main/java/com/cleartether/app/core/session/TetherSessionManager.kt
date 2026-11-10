package com.cleartether.app.core.session

import com.cleartether.app.core.proxy.TrafficMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Free-tier rules. A free session allows [sessionTime] **or** [sessionBytes], whichever comes
 * first. Usage carries over when the user stops and restarts, so toggling doesn't reset the
 * meter. The allowance refills once the tether has been idle for [cooldown].
 */
data class FreeTierPolicy(
    val sessionTime: Duration = 15.minutes,
    val sessionBytes: Long = 100L * 1024 * 1024,
    val cooldown: Duration = 10.minutes,
)

/** Persisted free-tier usage. */
data class QuotaSnapshot(
    val usedMs: Long = 0,
    val usedBytes: Long = 0,
    /** Wall-clock time the tether was last active (used to evaluate the cooldown). */
    val lastActiveWallMs: Long = 0,
)

/** Persistence for [QuotaSnapshot] (DataStore on device, in-memory in tests). */
interface QuotaStore {
    suspend fun load(): QuotaSnapshot
    suspend fun save(snapshot: QuotaSnapshot)
}

enum class LimitReason { TIME, DATA }

/** Whether a new session may start right now. */
sealed interface SessionEligibility {
    data class Allowed(val remainingMs: Long, val remainingBytes: Long, val unlimited: Boolean) : SessionEligibility
    data class CoolingDown(val availableAtWallMs: Long) : SessionEligibility
}

/** UI-facing snapshot of the running (or last) session. */
data class SessionState(
    val active: Boolean = false,
    val premium: Boolean = false,
    /** Duration of the current session. */
    val elapsedMs: Long = 0,
    val bytesUploaded: Long = 0,
    val bytesDownloaded: Long = 0,
    /** Free-tier allowance left (including usage carried over from earlier sessions). */
    val remainingMs: Long = 0,
    val remainingBytes: Long = 0,
    val timeLimitMs: Long = 0,
    val dataLimitBytes: Long = 0,
    val limitReached: LimitReason? = null,
    /** When a free session can start again (wall clock), if currently exhausted. */
    val nextFreeSessionAtWallMs: Long? = null,
) {
    val totalBytes: Long get() = bytesUploaded + bytesDownloaded
    val timeProgress: Float get() = if (timeLimitMs <= 0) 0f else (1f - remainingMs.toFloat() / timeLimitMs).coerceIn(0f, 1f)
    val dataProgress: Float get() = if (dataLimitBytes <= 0) 0f else (1f - remainingBytes.toFloat() / dataLimitBytes).coerceIn(0f, 1f)
}

/**
 * Tracks cumulative throughput and connection time for the tether and enforces [FreeTierPolicy].
 *
 * Hot path: [onTraffic] is called from every relay pump, so it only touches atomics. When the
 * data allowance runs out, [onLimitReached] fires **synchronously** on the reporting thread, so
 * the proxy can stop the very next byte. The time limit is checked by a 1 Hz ticker that also
 * publishes [state] for the UI and notification.
 */
class TetherSessionManager(
    private val store: QuotaStore,
    private val policy: FreeTierPolicy = FreeTierPolicy(),
    private val elapsedClock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : TrafficMeter {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _limitEvents = MutableSharedFlow<LimitReason>(extraBufferCapacity = 4)
    val limitEvents: SharedFlow<LimitReason> = _limitEvents.asSharedFlow()

    /** Invoked once per session, on the thread that crossed the limit. */
    @Volatile var onLimitReached: ((LimitReason) -> Unit)? = null

    private val uploaded = AtomicLong()
    private val downloaded = AtomicLong()
    private val limitFired = AtomicBoolean(false)

    @Volatile private var active = false
    @Volatile private var premium = false
    @Volatile private var startedAt = 0L
    @Volatile private var carriedMs = 0L
    @Volatile private var carriedBytes = 0L
    @Volatile private var limitReason: LimitReason? = null
    private var ticker: Job? = null

    /** Checks the persisted quota without starting anything. */
    suspend fun eligibility(isPremium: Boolean): SessionEligibility {
        if (isPremium) return SessionEligibility.Allowed(Long.MAX_VALUE, Long.MAX_VALUE, unlimited = true)
        val quota = effectiveQuota(store.load())
        val remainingMs = policy.sessionTime.inWholeMilliseconds - quota.usedMs
        val remainingBytes = policy.sessionBytes - quota.usedBytes
        return if (remainingMs > 0 && remainingBytes > 0) {
            SessionEligibility.Allowed(remainingMs, remainingBytes, unlimited = false)
        } else {
            SessionEligibility.CoolingDown(quota.lastActiveWallMs + policy.cooldown.inWholeMilliseconds)
        }
    }

    /**
     * Starts metering. Returns false (and starts nothing) if a free session is still cooling down.
     * [scope] hosts the ticker and should be the tether service scope.
     */
    suspend fun start(scope: CoroutineScope, isPremium: Boolean): Boolean {
        if (active) return true
        val quota = effectiveQuota(store.load())
        if (!isPremium && (quota.usedMs >= policy.sessionTime.inWholeMilliseconds || quota.usedBytes >= policy.sessionBytes)) {
            publish()
            return false
        }
        premium = isPremium
        carriedMs = quota.usedMs
        carriedBytes = quota.usedBytes
        uploaded.set(0)
        downloaded.set(0)
        limitFired.set(false)
        limitReason = null
        startedAt = elapsedClock()
        active = true
        publish()
        ticker = scope.launch {
            var sincePersist = 0
            while (isActive && active) {
                delay(TICK_MS)
                checkTimeLimit()
                publish()
                if (++sincePersist >= PERSIST_EVERY_TICKS) {
                    sincePersist = 0
                    persist()
                }
            }
        }
        return true
    }

    /** Unlocks (or re-locks) mid-session. Upgrading clears any limit that was hit. */
    fun setPremium(isPremium: Boolean) {
        premium = isPremium
        if (isPremium) {
            limitReason = null
            limitFired.set(false)
        }
        publish()
    }

    /** Stops metering and persists usage. Safe to call twice. */
    suspend fun stop() {
        if (!active) return
        checkTimeLimit()
        active = false
        ticker?.cancel()
        ticker = null
        persist()
        publish()
    }

    /** Refreshes [state] while idle so the UI can show allowance and cooldown. */
    suspend fun refreshIdleState(isPremium: Boolean) {
        if (active) return
        premium = isPremium
        val quota = effectiveQuota(store.load())
        carriedMs = quota.usedMs
        carriedBytes = quota.usedBytes
        uploaded.set(0)
        downloaded.set(0)
        val exhausted = quota.usedMs >= policy.sessionTime.inWholeMilliseconds || quota.usedBytes >= policy.sessionBytes
        limitReason = when {
            isPremium || !exhausted -> null
            quota.usedBytes >= policy.sessionBytes -> LimitReason.DATA
            else -> LimitReason.TIME
        }
        publish(lastActiveWallMs = quota.lastActiveWallMs)
    }

    override fun onTraffic(client: InetAddress, uploaded: Long, downloaded: Long) {
        if (!active) return
        if (uploaded > 0) this.uploaded.addAndGet(uploaded)
        if (downloaded > 0) this.downloaded.addAndGet(downloaded)
        if (!premium && carriedBytes + this.uploaded.get() + this.downloaded.get() >= policy.sessionBytes) {
            fireLimit(LimitReason.DATA)
        }
    }

    private fun checkTimeLimit() {
        if (!active || premium) return
        if (carriedMs + (elapsedClock() - startedAt) >= policy.sessionTime.inWholeMilliseconds) fireLimit(LimitReason.TIME)
    }

    private fun fireLimit(reason: LimitReason) {
        if (!limitFired.compareAndSet(false, true)) return
        limitReason = reason
        _limitEvents.tryEmit(reason)
        onLimitReached?.invoke(reason)
        publish()
    }

    private suspend fun persist() {
        val sessionMs = elapsedClock() - startedAt
        val snapshot = if (premium) {
            // Premium sessions don't consume the free allowance.
            QuotaSnapshot(carriedMs, carriedBytes, wallClock())
        } else {
            QuotaSnapshot(
                usedMs = (carriedMs + sessionMs).coerceAtMost(policy.sessionTime.inWholeMilliseconds),
                usedBytes = (carriedBytes + uploaded.get() + downloaded.get()).coerceAtMost(policy.sessionBytes),
                lastActiveWallMs = wallClock(),
            )
        }
        runCatching { store.save(snapshot) }
    }

    /** Applies the cooldown: an allowance idle for long enough refills completely. */
    private fun effectiveQuota(saved: QuotaSnapshot): QuotaSnapshot {
        val idleFor = wallClock() - saved.lastActiveWallMs
        return if (saved.lastActiveWallMs == 0L || idleFor >= policy.cooldown.inWholeMilliseconds || idleFor < 0) {
            QuotaSnapshot(0, 0, saved.lastActiveWallMs)
        } else {
            saved
        }
    }

    private fun publish(lastActiveWallMs: Long? = null) {
        val limitMs = policy.sessionTime.inWholeMilliseconds
        val elapsed = if (active) elapsedClock() - startedAt else 0L
        val up = uploaded.get()
        val down = downloaded.get()
        val reason = limitReason
        val nextFree = when {
            premium || reason == null -> null
            active -> wallClock() + policy.cooldown.inWholeMilliseconds
            else -> (lastActiveWallMs ?: wallClock()) + policy.cooldown.inWholeMilliseconds
        }
        _state.value = SessionState(
            active = active,
            premium = premium,
            elapsedMs = elapsed,
            bytesUploaded = up,
            bytesDownloaded = down,
            remainingMs = if (premium) Long.MAX_VALUE else (limitMs - carriedMs - elapsed).coerceAtLeast(0),
            remainingBytes = if (premium) Long.MAX_VALUE else (policy.sessionBytes - carriedBytes - up - down).coerceAtLeast(0),
            timeLimitMs = if (premium) 0 else limitMs,
            dataLimitBytes = if (premium) 0 else policy.sessionBytes,
            limitReached = if (premium) null else reason,
            nextFreeSessionAtWallMs = nextFree,
        )
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val PERSIST_EVERY_TICKS = 15
    }
}
