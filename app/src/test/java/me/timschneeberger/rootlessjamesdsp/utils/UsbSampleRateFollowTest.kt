package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import org.junit.Assert.*
import org.junit.Test

class UsbSampleRateFollowTest {
    private fun reading(rate: Int?=192000,frames: Int?=0,enabled: Boolean=true,identity: String="usb") =
        listOf(OutputRuntimeStatus.Session(identity,enabled,rate,frames))

    @Test fun usbAndPowerAloneNeverClaimProcessingUntilTheSameHostRateHasFrameProgress() {
        val follow=UsbSampleRateFollow()
        assertEquals(UsbSampleRateFollow.Stage.WAITING_USB,follow.update(true,false,reading()).status.stage)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,follow.update(true,true,reading()).status.stage)
        assertFalse(follow.update(true,true,reading()).synchronize)
        val active=follow.update(true,true,reading(frames=512))
        assertEquals(listOf(192000),active.status.rates)
        assertTrue(active.synchronize)
        assertFalse(follow.update(true,true,reading(frames=1024)).synchronize)
        val asleep=follow.update(true,true,reading(frames=1024))
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,asleep.status.stage)
        assertTrue(asleep.status.rates.isEmpty())
        assertFalse(follow.update(true,true,reading(frames=1536)).synchronize)
        assertFalse(follow.update(true,true,reading(frames=2048,enabled=false)).synchronize)
        assertFalse(follow.update(true,true,reading(frames=2560)).synchronize)
        assertFalse(follow.update(true,true,reading(frames=3072)).synchronize)
    }

    @Test fun realRateChangeResynchronizesOnlyAfterProgressAndCounterResetIsNotAudio() {
        val follow=UsbSampleRateFollow()
        follow.update(true,true,reading(frames=512))
        follow.update(true,true,reading(frames=1024))
        val changed=follow.update(true,true,reading(384000,1536))
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,changed.status.stage)
        assertFalse(changed.synchronize)
        val confirmed=follow.update(true,true,reading(384000,2048))
        assertEquals(listOf(384000),confirmed.status.rates)
        assertTrue(confirmed.synchronize)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,follow.update(true,true,reading(384000,0)).status.stage)
        assertFalse(follow.update(true,true,reading(384000,0)).synchronize)
        assertFalse(follow.update(true,true,reading(384000,512)).synchronize)
    }

    @Test fun usbExitDisableServiceRestartAndMasterOffDiscardAllPreviousEvidence() {
        val follow=UsbSampleRateFollow()
        follow.update(true,true,reading(frames=512))
        assertTrue(follow.update(true,true,reading(frames=1024)).synchronize)
        val off=follow.update(false,true,reading(frames=1536))
        assertEquals(UsbSampleRateFollow.Stage.OFF,off.status.stage);assertFalse(off.synchronize)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,follow.update(true,true,reading(frames=2048)).status.stage)
        assertTrue(follow.update(true,true,reading(frames=2560)).synchronize)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_USB,follow.update(true,false,reading(frames=3072)).status.stage)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,follow.update(true,true,reading(frames=3584)).status.stage)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,UsbSampleRateFollow().update(true,true,reading(frames=4096)).status.stage)
        assertFalse(follow.update(true,true,reading(frames=4096,enabled=false)).synchronize)
        assertFalse(follow.update(true,true,reading(frames=4608,enabled=false)).synchronize)
        assertFalse(follow.update(true,true,reading(frames=5120)).synchronize)
        assertTrue(follow.update(true,true,reading(frames=5632)).synchronize)
    }

    @Test fun missingEffectOrDiagnosticsAndReplacedSessionsStayPendingWithoutInventingUsbRates() {
        val follow=UsbSampleRateFollow()
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,follow.update(true,true,emptyList()).status.stage)
        for(rate in listOf(null,0,7999,768001)) {
            follow.update(true,true,reading(rate,512))
            val missing=follow.update(true,true,reading(rate,1024))
            assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,missing.status.stage)
            assertTrue(missing.status.rates.isEmpty());assertFalse(missing.synchronize)
        }
        assertFalse(follow.update(true,true,reading(768000,null)).synchronize)
        follow.update(true,true,reading(frames=512,identity="legacy"))
        assertTrue(follow.update(true,true,reading(frames=1024,identity="legacy")).synchronize)
        assertFalse(follow.update(true,true,reading(frames=1536,identity="enhanced")).synchronize)
        assertTrue(follow.update(true,true,reading(frames=2048,identity="enhanced")).synchronize)
        follow.update(true,true,emptyList())
        assertFalse(follow.update(true,true,reading(frames=2560,identity="enhanced")).synchronize)
        assertTrue(follow.update(true,true,reading(frames=3072,identity="enhanced")).synchronize)
    }

    @Test fun signed31BitCounterWrapKeepsProcessingButAnOrdinaryResetNeedsFreshEvidence() {
        val follow=UsbSampleRateFollow()
        follow.update(true,true,reading(frames=Int.MAX_VALUE-1023))
        assertTrue(follow.update(true,true,reading(frames=Int.MAX_VALUE-511)).synchronize)
        val wrapped=follow.update(true,true,reading(frames=0))
        assertEquals(UsbSampleRateFollow.Stage.ACTIVE,wrapped.status.stage)
        assertFalse(wrapped.synchronize)
        assertFalse(follow.update(true,true,reading(frames=512)).synchronize)
        assertEquals(UsbSampleRateFollow.Stage.WAITING_AUDIO,follow.update(true,true,reading(frames=0)).status.stage)
        assertFalse(follow.update(true,true,reading(frames=512)).synchronize)
    }
}
