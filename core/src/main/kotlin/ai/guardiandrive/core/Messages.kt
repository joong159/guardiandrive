package ai.guardiandrive.core

/**
 * 음성 문구. 원칙: 혼내지 않기, 한 문장, 지금 할 일 하나만.
 */
object Messages {
    // 내 운전 점검
    fun repeatedBrake(n: Int) = "최근 5분 동안 급제동이 ${n}번 있었어요. 앞차와 거리를 조금 더 두면 뒤차들도 편해져요."
    fun phantomBrake() = "방금 앞이 비어 있었는데 브레이크를 세게 밟았어요. 급제동은 뒤로 정체를 만들 수 있어요."
    fun unsignaled(d: Direction) = "${d.korean}으로 차선을 바꿀 땐 깜빡이를 먼저 켜 주세요."
    fun weaving(n: Int) = "최근 2분 동안 차선을 ${n}번 바꿨어요. 한 차선을 유지해도 도착 시간은 거의 같아요."
    fun hogging(speed: Int) = "1차선은 추월차로예요. 지금 ${speed}킬로로 달리고 있고 뒤에 차가 따라와요. 여유 될 때 오른쪽으로 옮겨볼까요?"
    fun slowFlow(gap: Int) = "주변 차들보다 ${gap}킬로 느리게 달리고 있어요. 뒤로 정체가 생길 수 있으니 흐름에 맞추거나 오른쪽 차로로 옮겨 볼까요?"
    fun yielded() = "양보 감사해요. 뒤차들 흐름이 풀렸어요."
    fun smooth() = "10분째 흐름을 아주 잘 타고 계세요."

    // 방어 운전 레이더
    fun cutIn(from: Direction) = "${from.korean} 차가 앞으로 급하게 들어왔어요. 속도를 줄여 거리를 두세요."
    fun erratic(where: Direction) = "${where.korean} 차가 불안하게 달려요. 거리를 두세요."

    // 사고 직후 안내 (한국도로공사 '비트박스' 순서)
    object Accident {
        const val askOk = "큰 충격이 감지됐어요. 괜찮으세요? 다쳤으면 '도와줘', 사고가 아니면 '아니야'라고 말해 주세요."
        const val cancelled = "알겠어요. 계속 안전하게 운전하세요."
        const val cancelledAfterEmergency = "알겠어요. 비상 연락처에 사고가 아니라고 다시 알렸어요. 계속 안전하게 운전하세요."
        const val askAgain = "괜찮으세요? 대답이 없으면 비상 연락처에 위치를 보낼게요."
        const val hazard = "먼저 비상등을 켜 주세요. 뒤차에 멈춰 있다는 걸 알려야 해요."
        const val hazardRemind = "아직 비상등이 꺼져 있어요. 핸들 가운데 위쪽 빨간 삼각형 버튼이에요."
        const val hazardConfirmed = "비상등 켜진 것 확인했어요."
        const val trunkConfirmed = "트렁크 열린 것 확인했어요."
        const val trunk = "트렁크를 열어 주세요. 차가 멈춰 있다는 표시가 한 번 더 돼요. 열면 자동으로 확인할게요."
        const val trunkUrban = "뒤차가 볼 수 있도록 트렁크를 열어 주세요. 열면 자동으로 확인할게요."
        const val evacuateHighway = "지금 바로 차 밖으로 나와 가드레일 바깥으로 대피하세요. 차 앞이나 뒤 도로 위에 서 있으면 위험해요."
        const val evacuateUrban = "주변을 확인하고 인도나 길가 안전한 곳으로 이동하세요."
        const val callHighway = "안전한 곳에서 112나 119에 신고하세요. 차를 옮겨야 하면 한국도로공사 1588-2504로 연락하면 가까운 안전지대까지 무료로 견인해 줘요."
        const val callUrban = "안전한 곳에서 다친 사람이 있으면 119, 사고 신고는 112, 그리고 보험사에 연락하세요."
        const val photos = "안전할 때만 사진을 남기세요. 양쪽 차 전체, 부딪힌 부분, 상대 차 번호판, 도로 바닥 흔적 순서예요. 다 됐으면 '완료'라고 말해 주세요."
        const val emergency = "비상 연락처에 지금 위치를 보냈어요. 움직일 수 있으면 비상등을 켜고 '괜찮아'라고 말해 주세요."
        const val done = "잘하셨어요. 사고 정리 리포트를 화면에 띄워 둘게요."

        fun remind(step: AccidentGuide.Step) = when (step) {
            AccidentGuide.Step.TRUNK -> "트렁크를 열었으면 '했어'라고 말해 주세요."
            AccidentGuide.Step.EVACUATE -> "차 안이나 도로 위에 있으면 위험해요. 안전한 곳으로 이동했으면 '나왔어'라고 말해 주세요."
            AccidentGuide.Step.CALL -> "신고를 마쳤으면 '했어'라고 말해 주세요."
            AccidentGuide.Step.PHOTOS -> "사진을 다 찍었으면 '완료'라고 말해 주세요."
            else -> ""
        }
    }
}
