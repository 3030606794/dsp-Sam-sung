package me.timschneeberger.rootlessjamesdsp.session.root

import org.junit.Assert.*
import org.junit.Test

class ActivePlaybackSessionParserTest {
    private val processes = """
        PID UID NAME
        1879 1041 audioserver
        24943 10392 com.neutroncode.mp
        26710 10377 com.dspfusion.android
        27000 10400 com.example.player:audio
    """.trimIndent()

    // Columns/rows copied from the Samsung SM-S9180 Android 15 routing captures.
    private fun samsung(rate: Int, sid: Int = 1537, pid: Int = 24943, usage: Int = 1,
                        stream: Int = 3, active: String = "yes", standby: String = "no") = """
        Global session refs:
          session cnt pid uid name
          0 1 26710 10377 com.dspfusion.android
          1537 1 24943 10392 com.neutroncode.mp
        Output thread 0xb400007eac917280, name AudioOut_15, tid 3391, type 0 (MIXER):
          I/O handle: 21
          Standby: $standby
          Sample rate: 48000 Hz
          1 Tracks of which ${if (active == "yes") 1 else 0} are active
            Type Id Active Client Session Port Id S Flags Format Chn mask SRate ST Usg CT G db L dB
                    247 $active $pid $sid 241 A 0x000 00000005 00000003 $rate $stream $usage 0 0 0
          1 Effect Chains
            1 effects for session 0
            Effect ID 131:
              Session State Registered Internal Enabled Suspended:
              00000 003 y n y n
          Local log:
           10-17 07:13:21.231 AT::add 218 yes 24943 1297 212 A 0x000 00000005 00000003 384000 3 1 0
    """.trimIndent()

    private fun sessions(dump: String, ps: String = processes) =
        ActivePlaybackSessionParser.sessions(dump, ps, 10377, "com.dspfusion.android")

    @Test fun samsungHdTrackHasThePlayersPositiveSessionRatherThanGlobalEffectSession() {
        assertEquals(listOf(ActivePlaybackSession(1537,10392,24943,"com.neutroncode.mp")),
            sessions(samsung(384000)))
    }

    @Test fun ordinary48kUsesItsOwnSessionAndDoesNotDependOnOutputSampleRate() {
        assertEquals(1433, sessions(samsung(48000,1433))!!.single().sessionId)
    }

    @Test fun usbExclusiveIdleEffectChainAndHistoricalTrackDoNotInventPlayback() {
        val idle = """
            Output thread 0xb400007eb9327c00, name AudioOut_D, tid 3354, type 0 (MIXER):
              Standby: yes
              0 Tracks of which 0 are active
              1 Effect Chains
                1 effects for session 0
                Effect ID 131:
                  Session State Registered Internal Enabled Suspended:
                  00000 003 y n y n
              Local log:
               AT::add 247 yes 24943 1537 241 A 0x000 00000005 00000003 384000 3 1 0
        """.trimIndent()
        assertTrue(sessions(idle)!!.isEmpty())
    }

    @Test fun inactiveStandbyZeroNegativeAndOwnSessionsAreIgnored() {
        assertTrue(sessions(samsung(48000,active="no"))!!.isEmpty())
        assertTrue(sessions(samsung(48000,standby="yes"))!!.isEmpty())
        assertTrue(sessions(samsung(48000,sid=0))!!.isEmpty())
        assertTrue(sessions(samsung(48000,sid=-2105))!!.isEmpty())
        assertTrue(sessions(samsung(48000,pid=26710))!!.isEmpty())
    }

    @Test fun mediaGameAndUnknownMusicAreAllowedButNotificationsAndCallsAreNot() {
        assertEquals(1,sessions(samsung(48000,usage=14))!!.size)
        assertEquals(1,sessions(samsung(48000,usage=0,stream=3))!!.size)
        assertTrue(sessions(samsung(48000,usage=5,stream=5))!!.isEmpty())
        assertTrue(sessions(samsung(48000,usage=2,stream=0))!!.isEmpty())
    }

    @Test fun aospTypeColumnAndHeadersWithoutPortIdAreParsedByColumnPosition() {
        val aosp = """
            Output thread 0x1234, name AudioOut_D, tid 200, type 0 (MIXER):
              Standby: no
              1 Tracks of which 1 are active
              Type Id Active Client Session S Flags Format Chn mask SRate ST Usg CT
              M 87 yes 27000 22 A 0x000 00000005 00000003 192000 3 1 2
        """.trimIndent()
        assertEquals(ActivePlaybackSession(22,10400,27000,"com.example.player"), sessions(aosp)!!.single())
    }

    @Test fun missingDumpMalformedActiveTableAndRacingPidAreFailureRatherThanEmpty() {
        assertNull(sessions("Permission Denial: can't dump media.audio_flinger"))
        assertNull(sessions(samsung(48000).replace("247 yes", "247 ???")))
        assertNull(sessions(samsung(48000), "PID UID NAME\n1879 1041 audioserver"))
        assertNull(sessions(samsung(48000), "ps: bad -o UID"))
    }

    @Test fun multiplePlayersAndDuplicateTracksKeepDistinctPositiveSessions() {
        val second=samsung(192000,sid=22,pid=27000)
        val result=sessions(samsung(384000)+"\n"+second+"\n"+second)!!
        assertEquals(setOf(1537,22),result.map{it.sessionId}.toSet())
    }

    @Test fun inputThreadSessionIsNotAPlaybackSession() {
        val input=samsung(48000).replace("Output thread", "Input thread")
        assertTrue(sessions(samsung(48000,active="no")+"\n"+input)!!.isEmpty())
    }
}
