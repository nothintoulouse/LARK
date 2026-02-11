package com.brayden.lark.audio

import android.util.Log

data class AudioPacketHeader(
    val packetNumber: Int,
    val fragmentIndex: Int,
    val audioData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPacketHeader) return false
        return packetNumber == other.packetNumber &&
                fragmentIndex == other.fragmentIndex &&
                audioData.contentEquals(other.audioData)
    }

    override fun hashCode(): Int {
        var result = packetNumber
        result = 31 * result + fragmentIndex
        result = 31 * result + audioData.contentHashCode()
        return result
    }
}

fun parseAudioPacket(data: ByteArray): AudioPacketHeader? {
    if (data.size < 3) {
        Log.w("PacketParser", "Packet too small: ${data.size} bytes")
        return null
    }

    val packetNumber = (data[1].toInt() and 0xFF shl 8) or (data[0].toInt() and 0xFF)
    val fragmentIndex = data[2].toInt() and 0xFF
    val audioData = data.copyOfRange(3, data.size)

    return AudioPacketHeader(packetNumber, fragmentIndex, audioData)
}

class PacketReassembler {

    companion object {
        private const val TAG = "PacketReassembler"
        private const val STALE_FRAME_AGE = 500L  // frames (5 seconds at 10ms/frame)
        private const val MAX_BUFFERED_PACKETS = 16
        private const val CLEANUP_INTERVAL = 100  // run stale cleanup every 100th frame
    }

    private data class FragmentEntry(
        val index: Int,
        val data: ByteArray,
        val frameCounter: Long  // monotonic frame counter instead of System.currentTimeMillis()
    )

    private val packetBuffer = mutableMapOf<Int, MutableList<FragmentEntry>>()
    private var lastCompletedPacket: Int = -1
    private var monotonicFrameCounter: Long = 0  // increments on every addFragment call
    private var cleanupCounter: Int = 0

    // Packet loss tracking
    var totalPackets = 0L
        private set
    var lostPackets = 0L
        private set

    fun addFragment(header: AudioPacketHeader): ByteArray? {
        monotonicFrameCounter++

        // Rate-limit stale cleanup: every 100th frame instead of every frame
        if (++cleanupCounter >= CLEANUP_INTERVAL) {
            cleanStalePackets()
            cleanupCounter = 0
        }

        val fragments = packetBuffer.getOrPut(header.packetNumber) { mutableListOf() }
        fragments.add(FragmentEntry(header.fragmentIndex, header.audioData, monotonicFrameCounter))

        // Single fragment packet (most common with large MTU)
        if (header.fragmentIndex == 0 && isNextFragment(header.packetNumber, fragments)) {
            return completePacket(header.packetNumber, fragments)
        }

        // Multi-fragment: check if sequential from 0
        if (header.fragmentIndex > 0 && isSequentialFromZero(fragments)) {
            return completePacket(header.packetNumber, fragments)
        }

        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isNextFragment(
        packetNumber: Int,
        fragments: List<FragmentEntry>
    ): Boolean {
        // If there's only one fragment (index 0), it might be complete
        // We assume a new packet number with index 0 means the previous packet is done
        // and this single-fragment packet is complete
        if (fragments.size == 1 && fragments[0].index == 0) {
            // Check if next packet starts - if buffer has only this packet, assume complete
            return packetBuffer.size <= MAX_BUFFERED_PACKETS
        }
        return false
    }

    private fun isSequentialFromZero(fragments: List<FragmentEntry>): Boolean {
        val sorted = fragments.sortedBy { it.index }
        return sorted.indices.all { i -> sorted[i].index == i }
    }

    private fun completePacket(packetNumber: Int, fragments: List<FragmentEntry>): ByteArray {
        // Track packet loss
        totalPackets++
        if (lastCompletedPacket >= 0) {
            val expected = (lastCompletedPacket + 1) % 65536
            if (packetNumber != expected) {
                val lost = if (packetNumber > lastCompletedPacket) {
                    packetNumber - lastCompletedPacket - 1
                } else {
                    (65536 - lastCompletedPacket) + packetNumber - 1
                }
                lostPackets += lost
                if (lost > 0) {
                    Log.w(TAG, "Lost $lost packets (total loss: $lostPackets/$totalPackets)")
                }
            }
        }
        lastCompletedPacket = packetNumber

        // Assemble fragments using System.arraycopy — zero GC overhead
        // (replaces the old flatMap { it.data.toList() }.toByteArray() which boxed every byte)
        val sorted = fragments.sortedBy { it.index }
        val totalSize = sorted.sumOf { it.data.size }
        val assembled = ByteArray(totalSize)
        var offset = 0
        for (frag in sorted) {
            System.arraycopy(frag.data, 0, assembled, offset, frag.data.size)
            offset += frag.data.size
        }

        packetBuffer.remove(packetNumber)
        return assembled
    }

    private fun cleanStalePackets() {
        val threshold = monotonicFrameCounter - STALE_FRAME_AGE
        val stale = packetBuffer.entries.filter { (_, fragments) ->
            fragments.any { it.frameCounter < threshold }
        }
        stale.forEach { (key, _) ->
            packetBuffer.remove(key)
            lostPackets++
            Log.w(TAG, "Dropped stale packet $key")
        }

        // Prevent unbounded growth
        while (packetBuffer.size > MAX_BUFFERED_PACKETS) {
            val oldest = packetBuffer.keys.minOrNull() ?: break
            packetBuffer.remove(oldest)
            lostPackets++
        }
    }

    fun getLossRate(): Float {
        val total = totalPackets + lostPackets
        return if (total > 0) (lostPackets.toFloat() / total * 100) else 0f
    }

    fun reset() {
        packetBuffer.clear()
        lastCompletedPacket = -1
        totalPackets = 0
        lostPackets = 0
        monotonicFrameCounter = 0
        cleanupCounter = 0
    }
}
