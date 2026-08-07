package dev.hyperears.runtime

import dev.hyperears.integration.AdapterControlResult
import dev.hyperears.integration.TargetedCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EarbudChannelTargetTest {

    @Test
    fun classicControlResultProjectsUntargetedFrames() {
        val result = AdapterControlResult(
            accepted = true,
            commands = listOf(byteArrayOf(1, 2), byteArrayOf(3)),
        )
        val frames = result.writeFrames()
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(1, 2), frames[0].bytes)
        assertNull(frames[0].targetId)
        assertArrayEquals(byteArrayOf(3), frames[1].bytes)
        assertNull(frames[1].targetId)
    }

    @Test
    fun targetedControlResultWinsOverClassicCommands() {
        val result = AdapterControlResult(
            accepted = true,
            commands = listOf(byteArrayOf(9)),
            targetedCommands = listOf(
                TargetedCommand(byteArrayOf(1, 2, 3), "ANC"),
                TargetedCommand(byteArrayOf(4, 5), "OFF"),
            ),
        )
        val frames = result.writeFrames()
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), frames[0].bytes)
        assertEquals("ANC", frames[0].targetId)
        assertArrayEquals(byteArrayOf(4, 5), frames[1].bytes)
        assertEquals("OFF", frames[1].targetId)
    }

    @Test
    fun defaultChannelWriteIgnoresTargetId() {
        val channel = RecordingChannel()
        runBlocking {
            channel.write(byteArrayOf(1, 2), "ANC")
            channel.write(byteArrayOf(3), null)
        }
        assertEquals(2, channel.writes.size)
        assertArrayEquals(byteArrayOf(1, 2), channel.writes[0])
        assertArrayEquals(byteArrayOf(3), channel.writes[1])
    }

    private class RecordingChannel : EarbudChannel {
        val writes = mutableListOf<ByteArray>()

        override val endpointId: String = "test"

        override suspend fun connect() = Unit

        override suspend fun read(buffer: ByteArray): Int = -1

        override suspend fun write(bytes: ByteArray) {
            writes += bytes
        }

        override fun close() = Unit
    }
}
