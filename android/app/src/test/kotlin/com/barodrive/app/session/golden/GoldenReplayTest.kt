package com.barodrive.app.session.golden

import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.ActiveSignalSource
import com.barodrive.app.data.store.DeviceBaroStore
import com.barodrive.app.data.voice.CoachVoiceLog
import com.barodrive.app.session.DrivingSessionManager
import com.barodrive.app.session.EngineSession
import com.barodrive.app.session.FacadeSessionEngine
import com.barodrive.app.session.SessionEngine
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.testing.FakeCoachVoice
import com.barodrive.app.testing.FakeNaviController
import com.barodrive.app.testing.FakeSessionRecordDao
import com.barodrive.app.testing.FakeSignalSource
import com.barodrive.app.testing.FakeSimLink
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Gear
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 골든 회차(prototype/tools/golden, 18개)를 세션 관리자에 다시 돌린다. 엔진은 그 회차에서 프로토타입 엔진이 낸 출력을 그대로 돌려준다
 * (엔진 포트와 상관없이 세션 층만 본다). 프로토타입 세션과 같아야 하는 것: 엔진에 넣는 스냅숏(수·세션 시각 t), 입력 때,
 * 회차를 끝내는 때(도착 뒤 P 표본 30~60개, 마지막 말 + 한 틱, 내비 음성 + 1초 — 마지막 골든 걸음 바로 뒤), 도착 표시.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoldenReplayTest {
    @Test
    fun sessionStepsAndFinishesLikeThePrototype() = runTest {
        val dir = goldenDir()
        assumeTrue("골든 파일을 찾지 못했어요(android/core/engine/src/test/resources/golden)", dir != null)
        val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
        val runs = manifest.getValue("runs").jsonArray.map { it.jsonObject }
        val problems = mutableListOf<String>()
        for (run in runs) {
            val id = run.getValue("id").jsonPrimitive.content
            val golden = Json.parseToJsonElement(gunzip(File(dir!!, run.getValue("file").jsonPrimitive.content))).jsonObject
            problems += replay(id, golden).map { "$id: $it" }
        }
        assertEquals("골든 ${runs.size}회차", emptyList<String>(), problems)
    }

    private suspend fun TestScope.replay(id: String, g: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        val snapshots = decodeSnapshots(g.getValue("snapshots").jsonArray)
        val outputs = decodeOutputs(g.getValue("outputs").jsonArray, snapshots).mapIndexed { i, o -> toOutput(o) { problems += "출력 $i: $it" } }
        val inputs = g.getValue("inputs").jsonArray.map { it.jsonObject }
        val arrivedArg = g.getValue("finish").jsonObject.getValue("arg").jsonObject.getValue("arrived").jsonPrimitive.boolean
        val record = BaroJson.decodeFromJsonElement(SessionRecord.serializer(), g.getValue("record"))
        val inputOutputs = inputs.map { toOutput(it.getValue("out").jsonObject) { e -> problems += "입력 출력: $e" } }
        val engine = ReplayEngine(snapshots, outputs, inputOutputs, record)

        val clock = Clock.fixed(Instant.parse("2026-11-10T00:12:00Z"), ZoneId.of("Asia/Seoul"))
        val settings = SettingsRepository(InMemoryDataStore(emptyPreferences()))
        val sim = FakeSignalSource()
        val signals = ActiveSignalSource(settings, FakeSignalSource(), sim, backgroundScope)
        val store = DeviceBaroStore(InMemoryDataStore(emptyPreferences()), FakeSessionRecordDao(), clock)
        val tts = FakeCoachVoice()
        val voice = CoachVoiceLog(tts, engine, store, backgroundScope)
        val manager = DrivingSessionManager(
            signals, engine, tts, voice, store, store, settings, FakeSimLink(), clock, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), FakeNaviController(),
        )
        manager.start()
        sim.awaitCollector()
        manager.prepare(PrepSession(mode = g.getValue("mode").jsonPrimitive.int))

        val vehicle = snapshots.map { BaroJson.decodeFromJsonElement(VehicleSnapshot.serializer(), it) }
        var finished: SessionEvent.Finished? = null
        manager.events.test {
            sim.emit(VehicleSnapshot(t = 0.4, gear = Gear.P, speed = 0.0)) // 세션 전 틱(골든 틱 4)
            var next = 0
            for ((i, snap) in vehicle.withIndex()) {
                while (next < inputs.size && inputs[next].getValue("afterSteps").jsonPrimitive.int == i) {
                    val ev = BaroJson.decodeFromJsonElement(SessionInput.serializer(), inputs[next].getValue("ev"))
                    if (manager.input(ev) == null) problems += "입력 ${next}을 받지 않음(회차 없음)"
                    next++
                }
                sim.emit(snap)
                runCurrent()
            }
            finished = cancelAndConsumeRemainingEvents()
                .mapNotNull { (it as? app.cash.turbine.Event.Item)?.value as? SessionEvent.Finished }
                .firstOrNull()
        }
        val session = engine.session
        if (session == null) return problems + "회차가 시작되지 않음"
        if (session.steps != snapshots.size) problems += "엔진 걸음 ${session.steps} ≠ 골든 ${snapshots.size}(끝내는 때가 다름)"
        if (session.finishedAtStep != snapshots.size) problems += "끝낸 때: 걸음 ${session.finishedAtStep} 뒤 ≠ 골든 ${snapshots.size} 뒤"
        if (session.finishedWith != arrivedArg) problems += "도착 ${session.finishedWith} ≠ 골든 $arrivedArg"
        if (session.inputCalls != inputs.size) problems += "입력 ${session.inputCalls} ≠ 골든 ${inputs.size}"
        problems += session.timeMismatch.take(3)
        if (finished?.recordId == null) problems += "기록이 저장되지 않음"
        return problems
    }

    /** 그 회차 프로토타입 엔진 출력을 그대로 돌려주는 엔진. 진행 반영 등 나머지는 진짜 파사드. */
    private class ReplayEngine(
        val snapshots: List<JsonObject>,
        val outputs: List<StepOutput>,
        val inputOutputs: List<StepOutput>,
        val record: SessionRecord,
        private val facade: FacadeSessionEngine = FacadeSessionEngine(),
    ) : SessionEngine by facade {
        var session: ReplaySession? = null

        override fun buildConfig(mode: Int, profile: Profile, prep: PrepSession, caps: SignalCaps): SessionConfig =
            SessionConfig(mode = mode, sessionNoInMode = prep.noInMode ?: 1)

        override fun createSession(cfg: SessionConfig): EngineSession = ReplaySession(cfg, this).also { session = it }
    }

    private class ReplaySession(override val config: SessionConfig, private val g: ReplayEngine) : EngineSession {
        var steps = 0
        var inputCalls = 0
        var finishedWith: Boolean? = null
        var finishedAtStep = -1
        val timeMismatch = mutableListOf<String>()

        override fun step(snapshot: VehicleSnapshot): StepOutput {
            val i = steps++
            val golden = g.snapshots.getOrNull(i) ?: return StepOutput(t = snapshot.t)
            val gt = golden.getValue("t").jsonPrimitive.double
            if (abs(gt - snapshot.t) > 1e-6) timeMismatch += "걸음 $i 세션 시각 ${snapshot.t} ≠ 골든 $gt"
            return g.outputs[i]
        }

        override fun input(event: SessionInput): StepOutput = g.inputOutputs.getOrElse(inputCalls++) { StepOutput() }

        override fun finish(arrived: Boolean): SessionRecord {
            finishedWith = arrived
            finishedAtStep = steps
            return g.record.copy(id = null, arrived = arrived)
        }
    }

    // ================================================================ 골든 파일 읽기(README 2·7.2·7.3장 — :core:engine 테스트의 GoldenStreams와 같은 규칙)

    private fun goldenDir(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            listOf("core/engine/src/test/resources/golden", "android/core/engine/src/test/resources/golden")
                .map { File(d, it) }.firstOrNull { File(it, "manifest.json").isFile }?.let { return it }
            d = d.parentFile
        }
        return null
    }

    private fun gunzip(f: File): String = GZIPInputStream(f.inputStream()).use { it.readBytes().decodeToString() }

    private fun round1(x: Double): Double = Math.round(x * 10).toDouble() / 10.0

    private fun JsonObject.deleted(): List<String> = (this[DEL] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun decodeSnapshots(encoded: JsonArray): List<JsonObject> {
        val out = ArrayList<JsonObject>(encoded.size)
        for (element in encoded) {
            val e = element.jsonObject
            val prev = out.lastOrNull()
            if (prev == null) {
                out += e
                continue
            }
            val del = e.deleted()
            val cur = LinkedHashMap<String, JsonElement>(prev)
            del.forEach { cur.remove(it) }
            for ((k, v) in e) {
                if (k == DEL) continue
                val p = prev[k]
                cur[k] = if (k == "nav" && p is JsonObject && v is JsonObject) {
                    val nav = LinkedHashMap<String, JsonElement>(p)
                    v.deleted().forEach { nav.remove(it) }
                    for ((nk, nv) in v) if (nk != DEL) nav[nk] = nv
                    JsonObject(nav)
                } else {
                    v
                }
            }
            if ("t" !in e && "t" !in del) cur["t"] = JsonPrimitive(round1(prev.getValue("t").jsonPrimitive.double + 0.1))
            out += JsonObject(cur)
        }
        return out
    }

    private fun decodeOutputs(encoded: JsonArray, snapshots: List<JsonObject>): List<JsonObject> {
        val out = ArrayList<JsonObject>(snapshots.size)
        var state = LinkedHashMap<String, JsonElement>()
        var j = 0
        for (i in snapshots.indices) {
            val e = encoded.getOrNull(j)?.jsonObject?.takeIf { it.getValue("i").jsonPrimitive.int == i }
            if (e != null) {
                j++
                state = LinkedHashMap(state)
                e.deleted().forEach { state.remove(it) }
                for ((k, v) in e) if (k != "i" && k != DEL && k != "t" && k !in LISTS) state[k] = v
            }
            val o = LinkedHashMap<String, JsonElement>()
            o["t"] = e?.get("t") ?: snapshots[i].getValue("t")
            for (k in LISTS) o[k] = e?.get(k) ?: JsonArray(emptyList())
            o.putAll(state)
            out += JsonObject(o)
        }
        return out
    }

    /** 엔진 출력 JSON → [StepOutput]. 모델로 못 풀면 세션이 쓰는 칸(say·blocked 없이·hints.arrived·muted·waiting)만 푼다. */
    private fun toOutput(o: JsonObject, onError: (String) -> Unit): StepOutput = try {
        BaroJson.decodeFromJsonElement(StepOutput.serializer(), o)
    } catch (e: IllegalArgumentException) {
        onError(e.message.orEmpty().take(200))
        StepOutput(
            t = o.getValue("t").jsonPrimitive.double,
            hints = com.barodrive.core.model.Hints(arrived = (o["hints"] as? JsonObject)?.get("arrived")?.jsonPrimitive?.boolean == true),
            muted = (o["muted"] as? JsonPrimitive)?.boolean == true,
        )
    }

    private companion object {
        const val DEL = "\$del"
        val LISTS = listOf("say", "blocked", "events")
    }
}
