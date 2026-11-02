package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class CrossfeedPreset(val id:String,val name:String,val settings:CrossfeedParameters.Settings)

object CrossfeedPresetCodec {
    const val FORMAT="dsp-audio-headphone-crossfeed-presets"
    const val MAX_BYTES=65536
    fun name(value:String):String{
        require(value.none {it.isISOControl()}){"名称不能包含换行或控制字符"}
        val n=value.trim();require(n.isNotEmpty() && n.length<=40){"名称需要 1–40 个字符"}
        require(n.indices.all {i->when {n[i].isHighSurrogate()->i+1<n.length && n[i+1].isLowSurrogate()
            n[i].isLowSurrogate()->i>0 && n[i-1].isHighSurrogate();else->true}}){"名称包含无效字符"}
        return n
    }
    fun read(input:InputStream):String{
        val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(true){val n=input.read(buffer);if(n<0)break
            if(n==0){val one=input.read();if(one<0)break;require(output.size()<MAX_BYTES){"预设文件不能超过 64 KiB"};output.write(one)}
            else{require(output.size()+n<=MAX_BYTES){"预设文件不能超过 64 KiB"};output.write(buffer,0,n)}}
        val bytes=output.toByteArray()
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun bounded(json:String){
        require(json.length<=MAX_BYTES && json.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"预设文件不能超过 64 KiB"}
        var depth=0;var quoted=false;var escaped=false
        for(c in json){if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else when(c){'"'->quoted=true;'[','{'->{depth++;require(depth<=8){"预设嵌套层级过多"}};']','}'->depth--}}
    }
    private fun JsonObject.fields(keys:Set<String>){require(this.keys==keys){"预设字段缺失或包含未知字段"}}
    private fun JsonObject.number(key:String)=(this[key] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull
        ?: throw IllegalArgumentException("参数 $key 必须为整数")
    private fun JsonObject.string(key:String)=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
        ?: throw IllegalArgumentException("字段 $key 必须为文本")
    private fun JsonObject.boolean(key:String)=(this[key] as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull
        ?: throw IllegalArgumentException("字段 $key 必须为开关")
    private val keys=setOf("legacy","professional","cutoff","attenuation","mix","delay","output","filter")
    fun decode(json:String,allowEmpty:Boolean=false):List<CrossfeedPreset>{
        bounded(json)
        val root=Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("预设必须为 JSON 对象")
        root.fields(setOf("format","version","units","presets"))
        val version=root.number("version")
        require(root.string("format")==FORMAT && version in 1..2){"不是受支持的耳机交叉馈送预设"}
        require(root.string("units")=="Hz,tenth-dB,percent,us"){"预设参数单位不受支持"}
        val entries=root["presets"] as? JsonArray ?: throw IllegalArgumentException("缺少预设列表")
        require(entries.size in (if(allowEmpty)0 else 1)..20){"每个文件需要包含 1–20 个预设"}
        val result=entries.map {e->
            val o=e as? JsonObject ?: throw IllegalArgumentException("预设条目必须为对象");o.fields(setOf("id","name","settings"))
            val id=o.string("id");require(id.length==36 && UUID.fromString(id).toString()==id){"预设标识无效"}
            val v=o["settings"] as? JsonObject ?: throw IllegalArgumentException("缺少参数");v.fields(if(version==1)keys else keys+"advanced")
            val advanced=if(version==1)CrossfeedAdvancedParameters.Settings()else {
                val words=v["advanced"] as? JsonArray ?: throw IllegalArgumentException("缺少专业增强参数")
                CrossfeedAdvancedParameters.Settings(words.map {word->(word as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull
                    ?: throw IllegalArgumentException("专业增强参数必须为整数")})
            }
            val s=CrossfeedParameters.Settings(v.number("legacy"),v.boolean("professional"),v.number("cutoff"),
                v.number("attenuation"),v.number("mix"),v.number("delay"),v.number("output"),v.number("filter"),advanced)
            require(s.valid()){"耳机交叉馈送参数超出范围"}
            CrossfeedPreset(id,name(o.string("name")),s)
        }
        require(result.map {it.id}.toSet().size==result.size && result.map {it.name}.toSet().size==result.size){"文件内预设标识或名称重复"}
        return result
    }
    fun encode(presets:List<CrossfeedPreset>,allowEmpty:Boolean=false):String{
        val json=buildJsonObject {
            put("format",FORMAT);put("version",2);put("units","Hz,tenth-dB,percent,us")
            putJsonArray("presets"){presets.forEach {p->add(buildJsonObject {
                put("id",p.id);put("name",name(p.name));putJsonObject("settings"){
                    val s=p.settings;put("legacy",s.legacy);put("professional",s.professional);put("cutoff",s.cutoff)
                    put("attenuation",s.attenuation);put("mix",s.mix);put("delay",s.delay);put("output",s.output);put("filter",s.filter)
                    putJsonArray("advanced"){s.advanced.words.forEach {add(JsonPrimitive(it))}}
                }
            })}}
        }.toString()
        decode(json,allowEmpty);return json
    }
}

/** Favorites live outside the device profile, other module libraries, and temporary comparison state. */
class CrossfeedPresetStore internal constructor(private val file:File){
    constructor(c:Context):this(File(c.applicationContext.filesDir,"headphone-crossfeed-favorites-v1.json"))
    companion object {private val lock=Any()}
    fun load():List<CrossfeedPreset> = synchronized(lock){
        val atomic=AtomicFile(file)
        val input=try{atomic.openRead()}catch(e:FileNotFoundException){
            if(!file.exists() && !File(file.path+".bak").exists())return@synchronized emptyList();throw e
        }
        input.use {CrossfeedPresetCodec.decode(CrossfeedPresetCodec.read(it),true)}
    }
    private fun write(values:List<CrossfeedPreset>){
        val bytes=CrossfeedPresetCodec.encode(values,true).toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs();val atomic=AtomicFile(file);val output=atomic.startWrite()
        try{
            output.write(bytes);output.fd.sync();atomic.finishWrite(output)
            if(!file.isFile || File(file.path+".new").exists() || !file.readBytes().contentEquals(bytes))
                throw IOException("无法提交耳机交叉馈送预设，请检查存储空间后重试")
        }catch(e:Exception){atomic.failWrite(output);throw e}
    }
    fun save(name:String,s:CrossfeedParameters.Settings):CrossfeedPreset = synchronized(lock){
        val n=CrossfeedPresetCodec.name(name);require(s.valid());val current=load()
        require(current.size<20){"个人预设已满 20 项"};require(current.none {it.name==n}){"名称已存在"}
        val p=CrossfeedPreset(UUID.randomUUID().toString(),n,s);write(current+p);p
    }
    fun rename(id:String,name:String)=synchronized(lock){
        val n=CrossfeedPresetCodec.name(name);val current=load();require(current.any {it.id==id}){"此预设已不存在"}
        require(current.none {it.id!=id && it.name==n}){"名称已存在"};write(current.map {if(it.id==id)it.copy(name=n)else it})
    }
    fun overwrite(id:String,s:CrossfeedParameters.Settings)=synchronized(lock){
        require(s.valid());val current=load();require(current.any {it.id==id}){"此预设已不存在"}
        write(current.map {if(it.id==id)it.copy(settings=s)else it})
    }
    fun delete(id:String)=synchronized(lock){val current=load();require(current.any {it.id==id}){"此预设已不存在"};write(current.filterNot {it.id==id})}
    fun export(id:String?=null):String=synchronized(lock){val current=load();val selected=if(id==null)current else current.filter {it.id==id}
        CrossfeedPresetCodec.encode(selected)}
    fun importJson(json:String):Int=synchronized(lock){
        val incoming=CrossfeedPresetCodec.decode(json);val current=load();require(current.size+incoming.size<=20){"导入后将超过 20 项，请先删除部分预设"}
        val names=current.map {it.name}.toMutableSet()
        val added=incoming.map {p->var name=p.name;var i=2
            while(name in names){val suffix=" ($i)";var prefix=p.name.take(40-suffix.length).trimEnd()
                if(prefix.lastOrNull()?.isHighSurrogate()==true)prefix=prefix.dropLast(1);name=prefix+suffix;i++}
            names+=name;p.copy(id=UUID.randomUUID().toString(),name=name)}
        write(current+added);added.size
    }
}
