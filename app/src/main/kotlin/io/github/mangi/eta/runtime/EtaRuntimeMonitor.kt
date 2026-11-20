package io.github.mangi.eta.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class EtaRuntimeProcess(
    val pid: Int,
    val name: String,
    val isMain: Boolean,
    val startTicks: Long?,
    val pssKb: Long?,
    val rssKb: Long?,
    val cpuPercent: Double?,
    val cpuReadable: Boolean,
    val memoryAgeMs: Long?,
)

internal data class EtaRuntimeSnapshot(
    val processes: List<EtaRuntimeProcess> = emptyList(),
    val sampled: Boolean = false,
    val discoveryLimited: Boolean = true,
) {
    val pssKb: Long? get() = EtaRuntimePolicy.sumKnown(processes.map { it.pssKb }.toTypedArray())
    val rssKb: Long? get() = EtaRuntimePolicy.sumKnown(processes.map { it.rssKb }.toTypedArray())
    val pssCount: Int get() = processes.count { it.pssKb != null }
    val rssCount: Int get() = processes.count { it.rssKb != null }
    val cpuCount: Int get() = processes.count { it.cpuPercent != null }
    val cpuPercent: Double? get() = processes.mapNotNull { it.cpuPercent }.takeIf { it.isNotEmpty() }?.sum()
}

/** Owned by one visible sheet lifecycle, never a service or global scope. All I/O on IO. */
internal class EtaRuntimeMonitor(context: Context) {
    private val app = context.applicationContext
    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val uid = Process.myUid()
    private val self = Process.myPid()
    private val cpu = EtaRuntimePolicy.CpuTracker()
    private val hz = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(-1L)
    private var lastDiscovery = Long.MIN_VALUE
    private var lastMemory = Long.MIN_VALUE
    private var cachedCandidates = setOf(self)
    private var limited = true
    private data class Memory(val start: Long?, val pss: Long?, val sampledAt: Long)
    private val memory = mutableMapOf<Int, Memory>()

    suspend fun sample(): EtaRuntimeSnapshot = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val now = SystemClock.uptimeMillis()
        // ActivityManager restricts visibility; it is not a global process census.
        val visible = runCatching { am?.runningAppProcesses }.getOrNull()
        val names = visible.orEmpty().filter { it.uid == uid }.associate { it.pid to it.processName }
        if (lastDiscovery == Long.MIN_VALUE || now - lastDiscovery >= 6_000) {
            val found = mutableSetOf(self)
            found.addAll(names.keys)
            // Metadata only for other UIDs. No cmdline, smaps, shell or root access.
            // Bounded low-frequency fallback discovers native same-UID children absent from AM.
            val entries = runCatching { File("/proc").listFiles() }.getOrNull()
                ?.filter { it.name.toIntOrNull()?.let { pid -> pid > 0 } == true }
            limited = entries == null || entries.size > MAX_DISCOVERY
            entries.orEmpty().take(MAX_DISCOVERY).forEach { directory ->
                coroutineContext.ensureActive()
                if (runCatching { Os.stat(directory.path).st_uid == uid }.getOrDefault(false)) {
                    directory.name.toIntOrNull()?.let { found.add(it) }
                }
            }
            cachedCandidates = found
            lastDiscovery = now
        }
        val refreshMemory = lastMemory == Long.MIN_VALUE || now - lastMemory >= 6_000
        val rows = mutableListOf<EtaRuntimeProcess>()
        var incomplete = limited || visible == null
        for (pid in (cachedCandidates + names.keys + self).sorted()) {
            coroutineContext.ensureActive()
            val beforeStat = EtaRuntimePolicy.parseStat(readProc(pid, "stat"))
            val beforeStatus = EtaRuntimePolicy.parseStatus(readProc(pid, "status"))
            val verified = EtaRuntimePolicy.belongsToUid(beforeStatus, uid) && beforeStat?.pid == pid
            if (!verified && pid != self) { incomplete = true; continue }
            // Self Debug.MemoryInfo is available without /proc or privileged APIs.
            if (refreshMemory || memory[pid]?.start != beforeStat?.startTicks) {
                val pss = if (pid == self) {
                    runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() }
                        .getOrNull()
                } else if (verified) {
                    runCatching { am?.getProcessMemoryInfo(intArrayOf(pid))?.firstOrNull()?.totalPss?.toLong() }
                        .getOrNull()
                } else null
                // Android can return a zeroed record on denial; zero PSS is not a live app measurement.
                memory[pid] = Memory(beforeStat?.startTicks, pss?.takeIf { it > 0 }, now)
            }
            val afterStatus = EtaRuntimePolicy.parseStatus(readProc(pid, "status"))
            val afterStat = EtaRuntimePolicy.parseStat(readProc(pid, "stat"))
            val stable = verified && EtaRuntimePolicy.sameIdentity(beforeStat, afterStat) &&
                EtaRuntimePolicy.belongsToUid(afterStatus, uid)
            if (!stable && pid != self) { incomplete = true; memory.remove(pid); continue }
            if (!stable) incomplete = true
            val pss = memory[pid]?.takeIf { pid == self || it.start == afterStat?.startTicks }
            rows += EtaRuntimeProcess(
                pid = pid,
                // Never read other process cmdlines (may contain private arguments).
                name = names[pid] ?: if (pid == self) app.packageName else "",
                isMain = pid == self,
                startTicks = afterStat?.startTicks,
                pssKb = pss?.pss,
                rssKb = if (stable) afterStatus?.rssKb else null,
                cpuPercent = if (stable) cpu.sample(afterStat, SystemClock.uptimeMillis() * 1_000_000L, hz) else null,
                cpuReadable = stable && hz > 0,
                memoryAgeMs = pss?.let { (now - it.sampledAt).coerceAtLeast(0) },
            )
        }
        if (refreshMemory) lastMemory = now
        val live = rows.map { it.pid }.toSet()
        memory.keys.retainAll(live)
        cpu.retain(live.toIntArray())
        EtaRuntimeSnapshot(rows.sortedWith(compareByDescending<EtaRuntimeProcess> { it.isMain }.thenBy { it.pid }), true, incomplete)
    }

    private fun readProc(pid: Int, leaf: String): String? = runCatching {
        File("/proc/$pid/$leaf").bufferedReader().use { reader ->
            val buffer = CharArray(16_384)
            val size = reader.read(buffer)
            if (size < 0) null else String(buffer, 0, size)
        }
    }.getOrNull()

    private companion object { const val MAX_DISCOVERY = 1024 }
}
