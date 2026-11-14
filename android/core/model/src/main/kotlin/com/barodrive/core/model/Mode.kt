package com.barodrive.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 연습 모드 1~8 (설계 0.2 '모드', 6.3.3 말하는 양, 프로토타입 data/copy.js `modes`). 순서는 고정이고 8은 늘 맨 끝.
 *
 * JSON에는 번호(1~8)로 쓴다. 기록·설정·프로필의 `mode` 칸은 프로토타입처럼 `Int`로 두고, 이름이 필요할 때 [Mode.of]로 바꾼다.
 */
@Serializable(with = ModeSerializer::class)
enum class Mode(
    /** 모드 번호 1~8. */
    val no: Int,
    /** 이름(목업 s01, copy.js `name`). */
    val title: String,
    /** 짧은 이름: '1단계'·'자율'·'주차'·'언덕'·'면허'. */
    val short: String,
    /** 홈 단계 막대 글자(단계 모드만, 그 밖은 ''). */
    val step: String,
    /** 홈 모드 카드 부제(목업 s01). */
    val where: String,
    /** 단계 연습(1~4)인지. 단계 통과·졸업은 이 모드만 센다(설계 6.10.4). */
    val isStage: Boolean,
    /** 말하는 양(설계 6.3.3 — RV-2 안내, FB-1 머리 설명). */
    val talk: String,
) {
    STAGE1(1, "차량 감 익히기", "1단계", "차량 감", "빈 주차장", true, "많이 말해요. 하기 전에 미리 알려 주고, 하고 나서 결과도 말해요."),
    STAGE2(2, "동네 골목 다니기", "2단계", "골목", "집 근처 골목", true, "미리 안내를 줄이고 결과 위주로 말해요."),
    STAGE3(3, "큰 도로 달리기", "3단계", "큰 도로", "시내 큰 도로", true, "위험한 것만 바로 말하고 나머지는 연습이 끝난 뒤 알려 줘요."),
    STAGE4(4, "고속도로 타 보기", "4단계", "고속도로", "IC 한두 구간", true, "달리는 중엔 거의 말하지 않고 끝난 뒤 알려 줘요."),
    AUTONOMOUS(5, "자율 모드", "자율", "", "원하는 곳까지 · 도착 점수", false, "3단계만큼 말해요. 항목은 내 음성 피드백 설정으로 골라요."),
    PARKING(6, "주차 연습", "주차", "", "평행·후방·전방", false, "주차 안내만 말해요."),
    HILL(7, "언덕 주행", "언덕", "", "근처 경사로", false, "언덕 단계 안내와 안전 항목만 바로 말해요."),
    LICENSE(8, "운전면허 연습 모드", "면허", "", "시험장 주변 코스", false, "2·3단계 과제를 2단계만큼 말해요."),
    ;

    /** '1단계 · 차량 감 익히기'(단계 모드) · '자율 모드'(그 밖). copy.js `label`. */
    val label: String get() = if (isStage) "$short · $title" else title

    companion object {
        /** 번호 → 모드. 1~8이 아니면 [IllegalArgumentException](프로토타입 `modeNum`과 같은 글). */
        fun of(no: Int): Mode = requireNotNull(ofOrNull(no)) { "모드는 1~8이에요: $no" }

        /** 번호 → 모드. 1~8이 아니면 null. */
        fun ofOrNull(no: Int): Mode? = entries.firstOrNull { it.no == no }

        /** 모드 번호 전부(1~8, 화면 순서). */
        val numbers: List<Int> get() = entries.map { it.no }
    }
}

/** [Mode]를 JSON 번호로 쓰고 읽는다. */
internal object ModeSerializer : KSerializer<Mode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.barodrive.core.model.Mode", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Mode) = encoder.encodeInt(value.no)

    override fun deserialize(decoder: Decoder): Mode = Mode.of(decoder.decodeInt())
}
