package com.barodrive.app.feature.parking

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.tooling.preview.Preview
import com.barodrive.app.R
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.RenderOptions
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// 주차(PK-*)·언덕(HL-*·RV-6) 화면이 같이 쓰는 글 도구. 언덕 패키지도 이 파일을 쓴다(두 기능 모두 이 작업 몫).

/**
 * 화면 글 하나 꺼내기 (Android 문자열 리소스, `res/values/strings_{parking,hill}.xml`). 화면 글을 만드는 순수 함수는 이것만 받는다 —
 * 화면에서는 [rememberTextRes], 테스트에서는 strings xml을 읽는 가짜를 넘겨 프로토타입 글과 그대로 맞춰 본다.
 */
fun interface TextRes {
    fun text(@StringRes id: Int, vararg args: Any): String
}

/** 지금 리소스로 [TextRes]. */
@Composable
fun rememberTextRes(): TextRes {
    val resources = LocalResources.current
    return remember(resources) { TextRes { id, args -> resources.getString(id, *args) } }
}

/**
 * 대사 글 (프로토타입 hill.js·review.js·parking-guide.js `lineText` = `BaroEngine.renderLine(key, coach, vars, honorific, { peek: true })`,
 * 못 그리면 ''). 엔진 파사드 대사를 순번을 넘기지 않고(peek) 읽는다 — 화면에 보이기만 하는 글이라 말 순번을 건드리지 않는다.
 */
internal object ModeLines {
    fun render(key: String?, coach: Coach, vars: Map<String, Any?> = emptyMap(), honorific: Honorific? = null): String {
        if (key.isNullOrEmpty()) return ""
        return try {
            BaroEngine.lines.render(key, coach, vars, honorific, RenderOptions(peek = true))
        } catch (e: IllegalArgumentException) {
            "" // 대사가 없거나 변수가 빠짐(LineRenderException)
        } catch (e: IllegalStateException) {
            "" // 콘텐츠를 읽지 못함(ContentException)
        }
    }

    /** 엔진 힌트의 JSON 변수 → 대사 변수(글자·수·참거짓). */
    fun vars(json: JsonObject?): Map<String, Any?> = json?.mapValues { (_, v) ->
        (v as? JsonPrimitive)?.let { p -> if (p.isString) p.content else p.booleanOrNull ?: p.doubleOrNull ?: p.content }
    } ?: emptyMap()
}

/** 모델 코치 → 화면 얼굴. */
internal fun Coach.face(): CoachFace = CoachFace.fromId(name.lowercase())

/** 걸린 시간 '45초' · '1분 23초' · '2분' (parking-guide.js·parking-virtual.js `fmtSec`). */
internal fun fmtSec(sec: Double?, res: TextRes): String {
    val s = (sec ?: 0.0).coerceAtLeast(0.0).let { jsRound(it) }
    if (s < 60) return res.text(R.string.pk_sec, s)
    val m = s / 60
    val r = s % 60
    return if (r != 0) res.text(R.string.pk_min_sec, m, r) else res.text(R.string.pk_min, m)
}

/** JS `Math.round`(반은 +∞ 쪽). */
internal fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

/** 보임 숫자: 정수면 그대로, 아니면 소수 한 자리(review.js `num1`). */
internal fun num1(x: Double): String {
    val v = floor(x * 10 + 0.5) / 10
    return if (abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
}

/** 주차·언덕 화면 미리보기: 세로 840×1184(다크·라이트)와 Pleos 에뮬레이터 가로 창 1682×1210(다크). */
@Preview(
    name = "세로 다크",
    widthDp = Dimens.WINDOW_WIDTH_DP,
    heightDp = Dimens.WINDOW_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFF131417,
)
@Preview(
    name = "세로 라이트",
    widthDp = Dimens.WINDOW_WIDTH_DP,
    heightDp = Dimens.WINDOW_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
)
@Preview(
    name = "가로 다크",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFF131417,
)
annotation class ModeScreenPreviews

/** Pleos 에뮬레이터 앱 창(가로) 1682×1210dp. */
const val LANDSCAPE_WIDTH_DP = 1682
const val LANDSCAPE_HEIGHT_DP = 1210
