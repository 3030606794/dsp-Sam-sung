package me.timschneeberger.rootlessjamesdsp.liveprog

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LiveprogTransferTest {
    @Test fun parameterPacketCarriesAsciiNameAndLittleEndianDoubleWithoutRounding() {
        val packet = LiveprogProtocol.parameter("fine_gain", 0.001)!!
        assertEquals(72, packet.size)
        assertEquals("fine_gain", String(packet.copyOfRange(0, 9), Charsets.US_ASCII))
        assertTrue(packet.copyOfRange(9, 64).all { it == 0.toByte() })
        assertEquals(0.001, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getDouble(64), 0.0)
    }

    @Test fun allocationAndChunkFailureStopBeforeCompile() {
        var chunks = 0
        var commits = 0
        val allocation = LiveprogScriptTransfer.send("a".repeat(600), { -12 }, { chunks++; 0 }, { commits++; 0 })
        assertEquals(-12, allocation.status)
        assertEquals(0, chunks)
        val transfer = LiveprogScriptTransfer.send("a".repeat(600), { 0 }, { chunks++; if (chunks == 2) -22 else 0 }, { commits++; 0 })
        assertEquals(-22, transfer.status)
        assertFalse(transfer.committed)
        assertEquals(2, chunks)
        assertEquals(0, commits)
    }

    @Test fun fullPacketsStillHaveTerminatorAndUtf8SourceIsPreserved() {
        listOf("x".repeat(256), "// 中文参数\n@init\ngain=0.001;\n@sample\nspl0*=gain;").forEach { source ->
            val packets = mutableListOf<ByteArray>()
            var count = 0
            val result = LiveprogScriptTransfer.send(source, { count = it[0]; 0 }, { packets.add(it); 0 }, { -22 })
            val bytes = packets.fold(ByteArray(0)) { all, part -> all + part }
            val expected = source.toByteArray(Charsets.UTF_8)
            assertArrayEquals(expected, bytes.copyOfRange(0, expected.size))
            assertEquals(0.toByte(), bytes[expected.size])
            assertEquals(count, packets.size)
            assertTrue(result.committed)
            assertEquals(-22, result.status)
        }
    }
}
