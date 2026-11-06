# 문서 안내

메뉴픽 문서 모음의 길잡이다. 파일은 한 폴더에 평평하게 둔다 — 소스 주석과 **불변 마이그레이션**
(`V5`·`V8`·`V9`·`V13`)이 `docs/DecisionLog.md`·`docs/PickPresetDesign.md` 같은 경로를 그대로
가리키고 있어, 하위 폴더로 옮기면 그 참조가 끊기고 마이그레이션 불변식(MigrationImmutabilityTest)도
깨진다. 그래서 물리적 폴더 대신 이 색인으로 묶는다.

## 이럴 땐 이 문서

| 알고 싶은 것 | 문서 |
| --- | --- |
| 지금 무엇이 배포돼 있고 서버 상태는 어떤가 | [CurrentStatus.md](CurrentStatus.md) |
| 이 기능/제약이 왜 이렇게 됐나 (근거·대안) | [DecisionLog.md](DecisionLog.md) (`D-0XX`) |
| API·도메인·에러 코드 같은 계약 | [Specification.md](Specification.md) |
| 무엇을 만들기로 했나 (요구사항·KPI) | [Requirements.md](Requirements.md) |
| 백엔드가 어떻게 설계됐나 | [Planning.md](Planning.md) |
| 아직 안 한 일·알려진 위험 | [ImprovementBacklog.md](ImprovementBacklog.md) |
| 배포·운영 절차(서버) | [../deploy/oci/README.md](../deploy/oci/README.md) |

## 문서 분류

### 상태·계획
- [CurrentStatus.md](CurrentStatus.md) — 현재 구현·배포·운영 상태의 스냅샷. 상세 이력은 `git log`·DecisionLog.
- [ImprovementBacklog.md](ImprovementBacklog.md) — 남은 과제와 알려진 위험.
- [DecisionLog.md](DecisionLog.md) — 설계·구현 결정의 근거와 검토한 대안(`D-0XX`, append-only).

### 요구·스펙
- [Requirements.md](Requirements.md) — 핵심 유저 스토리·수용 기준·KPI.
- [Specification.md](Specification.md) — 전체 스펙(기술 스택, API, 도메인, 에러 코드).
- [Planning.md](Planning.md) — 백엔드 기술 설계 계획서(가장 상세한 설계 문서).

### 기능별 설계 명세 (모두 운영 배포 완료)
- [PickPresetDesign.md](PickPresetDesign.md) — 상황별 빠른 픽(저장형 프리셋).
- [PickAlternativesDesign.md](PickAlternativesDesign.md) — 후보 없음 시 안전한 조건 조정 대안.
- [VisitCalendarDesign.md](VisitCalendarDesign.md) — 방문 캘린더.
- [FrontendDesignReview.md](FrontendDesignReview.md) — 프런트 화면 개선 방향과 참고.

### 테스트
- [LoadTestPlan.md](LoadTestPlan.md) — 부하 테스트 설계(무엇을 왜 재는가).
- [LoadTestResults.md](LoadTestResults.md) — 부하 테스트 회차별 결과 기록.
- [E2ETestPlan.md](E2ETestPlan.md) — 브라우저 E2E 테스트 경계와 시나리오.

### 운영·법무·프로세스
- [PrivacyReview.md](PrivacyReview.md) — 위치정보법·개인정보 고지 1차 검토(법률 자문 아님).
- [CommitConvention.md](CommitConvention.md) — 커밋 메시지 규칙(실제 이력 기준).

> 이미지는 [images/](images/)에 둔다. 문서를 옮기거나 이름을 바꾸면 소스 주석·마이그레이션이
> 가리키는 경로가 끊길 수 있으니, 핵심 문서의 파일명은 그대로 유지한다.
