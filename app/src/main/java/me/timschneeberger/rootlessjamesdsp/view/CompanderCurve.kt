package me.timschneeberger.rootlessjamesdsp.view

/** The setting curve uses the same PCHIP slopes as native interpolation.c.
 * This is a dimensionless coefficient preview, not an audio frequency response. */
internal object CompanderCurve {
    fun fill(frequencies: DoubleArray,coefficients: DoubleArray,queries: DoubleArray,result: FloatArray,count: Int): Boolean {
        if(frequencies.size!=7 || coefficients.size!=7 || count<0 || queries.size<count || result.size<count ||
            frequencies.any {!it.isFinite() || it<=0 || it>=24000} ||
            frequencies.asList().zipWithNext().any {(a,b)->a>=b} || coefficients.any {!it.isFinite() || it !in -1.2..1.2} ||
            (0 until count).any {!queries[it].isFinite() || queries[it] !in 0.0..24000.0})return false
        val x=doubleArrayOf(0.0)+frequencies+24000.0
        val y=doubleArrayOf(coefficients.first())+coefficients+coefficients.last()
        val slope=DoubleArray(9)
        slope[0]=(y[1]-y[0])/(x[1]-x[0]);slope[8]=(y[8]-y[7])/(x[8]-x[7])
        for(k in 1..7) {
            val previousWidth=x[k]-x[k-1];val nextWidth=x[k+1]-x[k]
            val previous=(y[k]-y[k-1])/previousWidth;val next=(y[k+1]-y[k])/nextWidth
            if(previous==0.0 || next==0.0 || (previous>0)!=(next>0))slope[k]=0.0
            else {val a=2*nextWidth+previousWidth;val b=nextWidth+2*previousWidth;slope[k]=(a+b)/(a/previous+b/next)}
        }
        for(i in 0 until count) {
            val q=queries[i]
            if(q==24000.0){result[i]=y[8].toFloat();continue}
            var end=1;while(end<8 && x[end]<=q)end++
            val start=end-1;val width=x[end]-x[start];val t=(q-x[start])/width
            result[i]=((1-t)*(1-t)*(y[start]*(1+2*t)+slope[start]*(q-x[start]))+
                t*t*(y[end]*(3-2*t)+width*slope[end]*(t-1))).toFloat()
        }
        return true
    }
}
