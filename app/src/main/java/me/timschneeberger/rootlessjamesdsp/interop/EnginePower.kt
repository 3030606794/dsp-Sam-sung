package me.timschneeberger.rootlessjamesdsp.interop

/** Saved user intent survives a failed getter, a failed write and native instance replacement. */
internal class EnginePower {
    enum class Result { DONE, RETRY, STOP }
    private var revision=0L
    private var failures=0
    @Volatile var desired=false
        private set
    @Volatile var pending=false
        private set
    @Synchronized fun request(value:Boolean) { desired=value;pending=true;revision++;failures=0 }
    @Synchronized fun replaced() { pending=true;revision++;failures=0 }
    fun apply(write:(Boolean)->Int):Result {
        val snapshot=synchronized(this){if(!pending)return Result.DONE;if(failures>=4)return Result.STOP;revision to desired}
        val status=runCatching {write(snapshot.second)}.getOrDefault(-3)
        return synchronized(this) {
            if(revision!=snapshot.first)return Result.RETRY
            if(status==0){pending=false;failures=0;Result.DONE}
            else{failures++;if(failures<4)Result.RETRY else Result.STOP}
        }
    }
}
