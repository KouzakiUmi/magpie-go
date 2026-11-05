package com.magpie.android.ui

import com.magpie.android.runtime.MagpieConfig
import com.magpie.android.runtime.MagpieEvent
import com.magpie.android.runtime.MagpieRuntime
import com.magpie.android.runtime.MagpieStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/** UI 测试用运行时：可切换阶段、推送事件，并记录调用次数。 */
class FakeMagpieRuntime(
    initialStatus: MagpieStatus = MagpieStatus(),
) : MagpieRuntime {
    private val incomingEvents = MutableSharedFlow<MagpieEvent>(extraBufferCapacity = 16)
    private val recentLogs = mutableListOf<String>()

    var currentStatus: MagpieStatus = initialStatus
        private set
    var installResult: Result<Unit> = Result.success(Unit)
    var startResult: Result<Unit> = Result.success(Unit)
    var stopResult: Result<Unit> = Result.success(Unit)
    var resetResult: Result<Unit> = Result.success(Unit)
    var installCalls = 0
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var lastConfig: MagpieConfig? = null
        private set

    override fun status(): MagpieStatus = currentStatus

    override fun events(): Flow<MagpieEvent> = flow {
        emit(MagpieEvent.Status(currentStatus))
        incomingEvents.collect { emit(it) }
    }

    override suspend fun install(): Result<Unit> {
        installCalls += 1
        return installResult
    }

    override suspend fun start(config: MagpieConfig): Result<Unit> {
        startCalls += 1
        lastConfig = config
        return startResult
    }

    override suspend fun stop(): Result<Unit> {
        stopCalls += 1
        return stopResult
    }

    override fun logs(lines: Int): List<String> = recentLogs.takeLast(lines)

    override suspend fun reset(keepData: Boolean): Result<Unit> = resetResult

    suspend fun emit(event: MagpieEvent) {
        if (event is MagpieEvent.Status) currentStatus = event.status
        incomingEvents.emit(event)
    }

    fun addLog(line: String) {
        recentLogs += line
    }
}
