package me.timschneeberger.rootlessjamesdsp.fusion

import android.media.audiofx.AudioEffect
import me.timschneeberger.rootlessjamesdsp.interop.DdcTransfer
import org.junit.Assert.*
import org.junit.Test

class DdcTransferTest {
    @Test fun bomAndFullOriginalBytesCrossPartitionBoundariesWithoutCharacterConversion() {
        val bytes=byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+ByteArray(510){(32+it%90).toByte()}
        val blocks=mutableListOf<ByteArray>();var prepared:IntArray?=null;var committed=0
        assertEquals(0,DdcTransfer.send(bytes,{prepared=it;0},{blocks+=it;0},{committed++;0}))
        assertArrayEquals(intArrayOf(3,256),prepared);assertEquals(3,blocks.size);assertEquals(1,committed)
        assertTrue(blocks.all {it.size==256})
        val joined=blocks.flatMap {it.toList()}.toByteArray()
        assertArrayEquals(bytes,joined.copyOf(bytes.size));assertTrue(joined.drop(bytes.size).all {it==0.toByte()})
    }
    @Test fun failedPreparationNeverSendsDataOrCommits() {
        var called=0
        assertEquals(-3,DdcTransfer.send(byteArrayOf(1),{-3},{called++;0},{called++;0}))
        assertEquals(0,called)
    }
    @Test fun failedMiddleBlockStopsBeforeTheCommitAndLaterBlocks() {
        var blocks=0;var commits=0
        assertEquals(-5,DdcTransfer.send(ByteArray(1000),{0},{if(++blocks==2)-5 else 0},{commits++;0}))
        assertEquals(2,blocks);assertEquals(0,commits)
    }
    @Test fun finalCommitFailureIsReturnedInsteadOfBeingCachedAsSuccess() {
        assertEquals(-7,DdcTransfer.send(ByteArray(300),{0},{0},{-7}))
    }
    @Test fun changedFileOrPowerDuringTransferCannotCommitTheObsoleteRequest() {
        var current=true;var blocks=0;var commits=0
        assertEquals(AudioEffect.ERROR_INVALID_OPERATION,DdcTransfer.send(ByteArray(600),{0},{blocks++;current=false;0},{commits++;0},{current}))
        assertEquals(1,blocks);assertEquals(0,commits)
        var preparations=0
        assertEquals(AudioEffect.ERROR_INVALID_OPERATION,DdcTransfer.send(byteArrayOf(1),{preparations++;0},{0},{0},{false}))
        assertEquals(0,preparations)
    }
    @Test fun cancellationAfterTheLastBlockStillCannotCommit() {
        var current=true;var commits=0
        assertEquals(AudioEffect.ERROR_INVALID_OPERATION,DdcTransfer.send(ByteArray(256),{0},{current=false;0},{commits++;0},{current}))
        assertEquals(0,commits)
    }
    @Test fun emptyOrOversizedInputNeverAllocatesADriverTransfer() {
        var called=0
        for(bytes in listOf(ByteArray(0),ByteArray(256*1024+1))) {
            assertEquals(AudioEffect.ERROR_BAD_VALUE,DdcTransfer.send(bytes,{called++;0},{called++;0},{called++;0}))
        }
        assertEquals(0,called)
    }
}
