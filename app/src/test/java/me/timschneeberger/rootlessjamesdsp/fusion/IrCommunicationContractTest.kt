package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor
import me.timschneeberger.rootlessjamesdsp.interop.ImpulseResponseTransfer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The raw stereo lengths reported by users must retain every sample across padded wire blocks. */
class IrCommunicationContractTest {
    @Test fun originalLongStereoKernelsKeepFrameCountBitsAndCrcAfterWireReassembly() {
        for (frames in listOf(16383, 18824)) {
            val source = FloatArray(frames * 2) { i ->
                if (i % 2 == 0) ((i % 157) - 78) / 256f else ((i % 251) - 125) / 512f
            }
            val blocks = (source.size + 4095) / 4096
            val wire = ByteBuffer.allocate(blocks * 16384).order(ByteOrder.nativeOrder())
            var count = 0
            var commits = 0
            assertEquals(0, ImpulseResponseTransfer.send(source, 2, { header ->
                assertArrayEquals(intArrayOf(frames * 2, 2, 0, blocks), header)
                0
            }, { chunk ->
                assertEquals(4096, chunk.size)
                chunk.forEach(wire::putFloat)
                count++
                0
            }, { commits++; 0 }))
            assertEquals(blocks, count)
            assertEquals(1, commits)
            assertEquals(0, wire.position() % 4)
            wire.flip()
            val received = FloatArray(source.size) { wire.float }
            assertArrayEquals(source, received, 0f)
            assertEquals(ConvolutionIrPreprocessor.crc(source), ConvolutionIrPreprocessor.crc(received))
            while (wire.hasRemaining()) assertEquals(0, wire.int)
        }
    }
}
