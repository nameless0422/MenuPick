import { useDeferredValue, useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { requestPick, type PickRequest, type PickResult } from "../api/pick";
import { searchTags } from "../api/tags";
import type { TagSummary } from "../api/menus";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { chipAction, chipClass, chipToggle } from "../a11y/chipToggle";
import { useRadioGroup } from "../a11y/radioGroup";
import { CATEGORY_PRESETS } from "../constants";
import KakaoMap from "../maps/KakaoMap";
import "./PickPage.css";

const SLOT_EMOJIS = ["🍚", "🍜", "🍕", "🍣", "🍔", "🥘", "🍝", "🌮", "🍗", "🥟", "🍛", "🥗"];
const SPIN_MS = 1200; // 슬롯머신 연출 최소 시간 — 응답이 더 빨라도 이만큼은 돌린다
const DISTANCE_OPTIONS = [300, 500, 1000, 2000];

type GeoState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; latitude: number; longitude: number }
  | { status: "error" };

export default function PickPage() {
  // 필터 <section>을 랜드마크로 세우려면 이름이 필요하다 — 그 이름을 주는 제목의 id.
  const filtersHeadingId = useId();

  // ---- 필터 상태 ----
  const [categories, setCategories] = useState<string[]>([]);
  const [includeTags, setIncludeTags] = useState<TagSummary[]>([]);
  const [excludeTags, setExcludeTags] = useState<TagSummary[]>([]);
  const [geo, setGeo] = useState<GeoState>({ status: "idle" });
  const [maxDistance, setMaxDistance] = useState(500);
  // 거리 선택지는 <legend>거리</legend>가 이름을 준다 — radiogroup은 fieldset 밖의
  // 별도 요소라 legend가 자동으로 붙지 않는다.
  const distanceLabelId = useId();
  const distanceGroup = useRadioGroup(DISTANCE_OPTIONS, maxDistance, setMaxDistance);

  // ---- 슬롯머신 연출 상태 ----
  const [spinning, setSpinning] = useState(false);
  const [slot, setSlot] = useState("🎲");
  const spinStartRef = useRef(0);
  const spinTimerRef = useRef<number | undefined>(undefined);

  // 초당 12.5회 이모지 교체는 전정기관이 민감한 사용자에게 불필요한 부하다. 연출만 빼고
  // 최소 대기(SPIN_MS)와 "메뉴를 뽑는 중…" 통지는 그대로 두므로 흐름은 달라지지 않는다.
  // 이 앱의 유일한 모션이라 CSS 쪽에는 대응할 것이 없다.
  // 마운트할 때 한 번만 읽는다 — 렌더마다 matchMedia를 부를 이유가 없고, 세션 도중
  // 설정을 바꾸는 일은 드물다(바꾸면 새로고침으로 반영된다).
  const [reduceMotion] = useState(
    () => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false,
  );

  useEffect(() => {
    if (!spinning || reduceMotion) return;
    const id = window.setInterval(() => {
      setSlot(SLOT_EMOJIS[Math.floor(Math.random() * SLOT_EMOJIS.length)]);
    }, 80);
    return () => window.clearInterval(id);
  }, [spinning, reduceMotion]);

  // 언마운트 시 결과 공개 타이머 정리
  useEffect(() => () => window.clearTimeout(spinTimerRef.current), []);

  const pickMutation = useMutation({
    mutationFn: (request: PickRequest) => requestPick(request),
    onSettled: () => {
      // 응답이 최소 연출 시간보다 빨리 오면 남은 시간만큼 더 돌리고 나서 공개
      const remaining = Math.max(0, SPIN_MS - (Date.now() - spinStartRef.current));
      window.clearTimeout(spinTimerRef.current);
      spinTimerRef.current = window.setTimeout(() => setSpinning(false), remaining);
    },
  });

  // 돌리는 중인지. disabled 대신 이 값으로 aria-busy/aria-disabled를 주고 핸들러에서
  // 조기 반환한다 — disabled는 초점을 받지 못해 누른 그 버튼에서 <body>로 떨어뜨린다.
  const busy = spinning || pickMutation.isPending;
  const pickButton = useRef<HTMLButtonElement>(null);

  const buildRequest = (): PickRequest => ({
    ...(categories.length > 0 && { categories }),
    ...(includeTags.length > 0 && { tagIds: includeTags.map((t) => t.id) }),
    ...(excludeTags.length > 0 && { excludeTagIds: excludeTags.map((t) => t.id) }),
    ...(geo.status === "ready" && {
      latitude: geo.latitude,
      longitude: geo.longitude,
      maxDistance,
    }),
  });

  const spin = () => {
    // aria-disabled는 표시일 뿐 클릭을 막지 않는다. 이 조기 반환이 실제 방어선이다.
    if (busy) return;
    // "다시 돌리기"는 결과 카드 안에 있는데, 돌리기 시작하면 결과가 감춰지며 그 카드가
    // 통째로 사라진다 — 누른 버튼이 없어져 초점이 <body>로 떨어진다. 지금 돌아가는 픽
    // 버튼으로 옮기면 진행 상태가 그대로 읽히고, 끝난 자리에서 바로 다시 돌릴 수 있다.
    // 픽 버튼에서 눌렀다면 이미 그 버튼이므로 아무 일도 일어나지 않는다.
    pickButton.current?.focus();
    spinStartRef.current = Date.now();
    setSpinning(true);
    pickMutation.mutate(buildRequest());
  };

  // 거리 필터 토글: 켤 때 현재 위치를 얻고, 실패하면 비활성 상태로 안내만 남긴다.
  //
  // getCurrentPosition은 취소할 방법이 없다(최대 10초까지 매달린다). 그래서 요청마다 세대 번호를
  // 매기고, 콜백이 돌아왔을 때 자기 세대가 아직 최신인지 확인한다. 이 가드가 없으면:
  //  - 껐는데 뒤늦게 성공 콜백이 도착해 체크박스가 스스로 다시 켜지고, 사용자가 끈 거리 필터가
  //    다음 픽에 조용히 적용된다.
  //  - 껐다 켜기를 반복하면 먼저 보낸 요청이 나중에 끝나면서 오래된 좌표가 최신 좌표를 덮어쓴다.
  //  - 껐는데 뒤늦게 실패 콜백이 도착해, 쓰지도 않는 필터의 권한 오류 안내가 뜬다.
  const geoGeneration = useRef(0);

  // 언마운트된 뒤 도착하는 콜백도 같은 방식으로 무효화한다.
  useEffect(() => () => { geoGeneration.current += 1; }, []);

  const toggleDistanceFilter = () => {
    // 끄는 경우에도 세대를 올려야 진행 중이던 요청의 결과가 되살아나지 않는다.
    const generation = ++geoGeneration.current;
    const isCurrent = () => geoGeneration.current === generation;

    if (geo.status === "ready" || geo.status === "loading") {
      setGeo({ status: "idle" });
      return;
    }
    if (!("geolocation" in navigator)) {
      setGeo({ status: "error" });
      return;
    }
    setGeo({ status: "loading" });
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        if (!isCurrent()) return;
        setGeo({
          status: "ready",
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
        });
      },
      () => {
        if (!isCurrent()) return;
        setGeo({ status: "error" });
      },
      { timeout: 10_000 },
    );
  };

  const revealed = !busy;
  const result = revealed && pickMutation.isSuccess ? pickMutation.data : null;
  const error = revealed && pickMutation.isError ? pickMutation.error : null;
  const noCandidates = error != null && apiErrorCode(error) === "NO_PICK_CANDIDATES";

  return (
    <div className="page">
      <header className="page-header">
        <h1>오늘 뭐 먹지</h1>
      </header>

      {/* 이름 없는 <section>은 랜드마크로 노출되지 않는다 — 스크린리더의 랜드마크 목록에
          잡히지 않아 실질적으로 <div>였고, 필터 뭉치를 건너뛰어 뽑기 버튼으로 가거나
          반대로 필터로 되돌아오려면 fieldset 네 개를 Tab으로 헤집는 수밖에 없었다.
          제목은 화면에 내지 않는다 — 눈으로 보는 사람에게는 각 fieldset의 legend
          (카테고리·포함 태그·제외 태그·거리)가 이미 구조를 보여주고 있어, 그 위에
          "픽 조건"을 한 줄 더 세우면 같은 말을 두 번 하는 셈이 된다. */}
      <section className="pick-filters" aria-labelledby={filtersHeadingId}>
        <h2 className="sr-only" id={filtersHeadingId}>픽 조건</h2>
        <CategoryFilter selected={categories} onChange={setCategories} />
        <TagFilter
          legend="포함 태그"
          placeholder="이 태그가 모두 있는 메뉴만 (예: 혼밥)"
          selected={includeTags}
          disabledTags={excludeTags}
          onChange={setIncludeTags}
        />
        <TagFilter
          legend="제외 태그"
          placeholder="이 태그가 있는 메뉴는 제외 (예: 느끼함)"
          selected={excludeTags}
          disabledTags={includeTags}
          onChange={setExcludeTags}
        />

        <fieldset>
          <legend id={distanceLabelId}>거리</legend>
          <label className="pick-distance-toggle">
            <input
              type="checkbox"
              checked={geo.status === "ready" || geo.status === "loading"}
              onChange={toggleDistanceFilter}
            />
            내 위치 기준으로 가까운 곳만
            {geo.status === "loading" && <small>위치 확인 중…</small>}
          </label>
          {/* 최대 10초 뒤에 비동기로 나타나고, 그때 체크박스가 스스로 꺼진다. 알리지 않으면
              스크린리더 사용자에게는 이유 없이 되돌아간 것으로만 보인다. */}
          {geo.status === "error" && (
            <p className="pick-geo-error" role="alert">
              위치를 가져오지 못해 거리 필터를 쓸 수 없어요. 브라우저의 위치 권한을 확인해 주세요.
            </p>
          )}
          {geo.status === "ready" && (
            /* 여기는 토글 버튼 무리가 아니라 단일 선택 그룹이다 — 항상 정확히 하나가
               선택돼 있고 해제할 수단이 없다. aria-pressed로 두면 스크린리더가
               "500m 이내, 눌림"만 읽고 방금 300m가 풀렸다는 사실은 전달되지 않아,
               선택이 옮겨 간 것이 아니라 하나가 더 켜진 것처럼 들린다.
               역할과 함께 화살표 키 이동·roving tabindex도 온다 — 근거는 useRadioGroup. */
            <div className="chip-row" {...distanceGroup.groupProps} aria-labelledby={distanceLabelId}>
              {DISTANCE_OPTIONS.map((meters, index) => (
                <button
                  key={meters}
                  type="button"
                  className={chipClass(maxDistance === meters)}
                  {...distanceGroup.radioProps(meters, index)}
                >
                  {formatDistance(meters)} 이내
                </button>
              ))}
            </div>
          )}
        </fieldset>
      </section>

      <div className="pick-stage">
        {/* 돌리는 동안 유일한 자식이 aria-hidden이라 접근 가능한 이름이 빈 문자열이 됐다.
            이름은 "지금 무엇을 하는 버튼인가"이므로 상태에 따라 바뀌면 안 된다 — 눈에만
            안 보이게 남겨 두면 돌아가는 중에도 같은 버튼으로 읽힌다. 진행 여부는 이름이
            아니라 aria-busy가 알린다. */}
        <button
          ref={pickButton}
          className="pick-button"
          onClick={spin}
          aria-busy={busy}
          aria-disabled={busy}
        >
          {busy ? (
            <>
              <span className="pick-slot" aria-hidden="true">{reduceMotion ? "🎲" : slot}</span>
              <span className="sr-only">오늘의 메뉴 뽑기</span>
            </>
          ) : (
            <>🎲 오늘의 메뉴 뽑기</>
          )}
        </button>

        {pickMutation.isIdle && (
          <p className="card-muted-hint">
            아직 메뉴가 없다면 <Link to="/menus">내 메뉴</Link>에서 자주 먹는 메뉴부터 등록해 보세요.
          </p>
        )}
      </div>

      {/* 픽 결과는 화면 일부만 조용히 바뀐다 — 알림이 없으면 스크린리더 사용자는
          버튼을 눌러도 무엇이 뽑혔는지 알 수 없다. 이 앱의 핵심 동작이라 반드시 필요하다. */}
      <div role="status" aria-live="polite">
        {/* 뽑는 동안 이 리전이 비어 있으면 Enter를 친 뒤 최소 1.2초가 완전한 무음이 된다.
            돌아가는 이모지는 aria-hidden이라 들리는 것이 하나도 없다. */}
        {(spinning || pickMutation.isPending) && <p className="sr-only">메뉴를 뽑는 중…</p>}
        {result && <PickResultCard result={result} onRetry={spin} />}

        {noCandidates && (
          <div className="card pick-empty">
            <p>조건에 맞는 메뉴가 없어요 — 필터를 풀거나 메뉴를 추가해 보세요.</p>
            <Link to="/menus">내 메뉴 관리하러 가기 →</Link>
          </div>
        )}
      </div>
      {error && !noCandidates && <p className="error" role="alert">{apiErrorMessage(error)}</p>}
    </div>
  );
}

// retrying prop이 있었지만 늘 false였다 — 돌리기 시작하면 result가 null이 되어 이 카드가
// 먼저 사라지기 때문이다. 초점은 대신 spin()이 픽 버튼으로 옮긴다.
function PickResultCard({
  result,
  onRetry,
}: {
  result: PickResult;
  onRetry: () => void;
}) {
  const { menu, restaurants } = result;
  // 이 카드는 픽이 도착한 순간에 마운트되므로, 마운트 시각이 곧 뽑은 시각이다.
  // 렌더마다 new Date()를 부르면 리렌더할 때마다 표에 찍힌 시각이 바뀐다 —
  // 초기화 함수로 한 번만 붙잡아 둔다.
  const [pickedAt] = useState(() => new Date());
  return (
    <div className="card pick-result">
      {/* 발권부. 높이가 PickPage.css의 --perf-y와 맞물려 있어 안쪽 글자는 줄바꿈되면 안 된다. */}
      <div className="pick-ticket-stub">
        <span className="pick-result-label">오늘의 픽 🎉</span>
        {/* 히스토리는 시각순으로 쌓이고 같은 메뉴가 여러 번 나올 수 있다 — 이 표를
            나중에 히스토리에서 짚으려면 이름이 아니라 시각이 필요하다. */}
        <time className="pick-ticket-time" dateTime={pickedAt.toISOString()}>
          {formatTime(pickedAt)}
        </time>
      </div>
      <div className="pick-ticket-body">
      <strong className="pick-result-name">{menu.name}</strong>
      {menu.memo && <p className="pick-result-memo">{menu.memo}</p>}

      {(menu.categories.length > 0 || menu.tags.length > 0) && (
        <div className="chip-row">
          {menu.categories.map((category) => (
            <span key={category} className="chip">{category}</span>
          ))}
          {menu.tags.map((tag) => (
            <span key={tag.id} className="chip chip-tag">#{tag.name}</span>
          ))}
        </div>
      )}

      {/* 추천 식당은 목록에 그대로 두고 지도를 덧붙인다. 지도가 안 떠도(키 미설정·로드 실패)
          어디로 가야 할지는 목록만으로 알 수 있어야 한다. */}
      <KakaoMap points={restaurants} ariaLabel={`${menu.name} 추천 식당 위치`} />

      {/* 지도를 못 보는 사람에게는 이 목록이 유일한 경로다. 몇 곳인지부터 알려야 한다.
          Safari + VoiceOver는 list-style: none이 걸린 <ul>의 목록 시맨틱을 지우므로
          role도 명시한다. */}
      {restaurants.length > 0 && (
        <>
        <p className="sr-only">{`추천 식당 ${restaurants.length}곳`}</p>
        <ul className="pick-restaurants" role="list">
          {restaurants.map((restaurant) => (
            <li key={restaurant.id}>
              <strong>{restaurant.name}</strong>
              {restaurant.address && <span className="pick-restaurant-address">{restaurant.address}</span>}
              {restaurant.distance != null && (
                <span className="chip">{formatDistance(restaurant.distance)}</span>
              )}
            </li>
          ))}
        </ul>
        </>
      )}

      <div className="card-actions">
        <button onClick={onRetry}>🔁 다시 돌리기</button>
        <Link to="/history">히스토리 보기 →</Link>
      </div>
      </div>
    </div>
  );
}

function CategoryFilter({
  selected,
  onChange,
}: {
  selected: string[];
  onChange: (categories: string[]) => void;
}) {
  const [custom, setCustom] = useState("");
  // 직접 입력한 카테고리는 selected에만 존재해서, 칩을 눌러 해제하는 순간 목록에서 영구히
  // 사라졌다 — 다시 쓰려면 처음부터 타이핑해야 하고, 방금 누른 버튼이 없어지니 초점도
  // <body>로 떨어졌다. 입력한 값을 따로 기억해 두면 칩이 언마운트되지 않으므로 두 문제가
  // 회피가 아니라 소멸로 해결된다. 프리셋 칩은 원래 목록에 남아 있어 같은 문제가 없다.
  const [customs, setCustoms] = useState<string[]>([]);
  const customInput = useRef<HTMLInputElement>(null);

  const toggle = (category: string) =>
    onChange(
      selected.includes(category)
        ? selected.filter((c) => c !== category)
        : [...selected, category],
    );

  const addCustom = () => {
    const value = custom.trim();
    setCustom("");
    // "추가"를 누르면 입력이 비어 그 버튼이 곧바로 disabled가 된다 — 방금 누른 버튼이
    // 초점을 받을 수 없게 되면서 초점이 <body>로 떨어진다. 다음 행동(다른 카테고리 입력)이
    // 시작되는 입력으로 옮기면 그 자리에서 이어서 칠 수 있다.
    customInput.current?.focus();
    if (!value) return;
    setCustoms((prev) => (prev.includes(value) ? prev : [...prev, value]));
    if (!selected.includes(value)) onChange([...selected, value]);
  };

  return (
    <fieldset>
      <legend>카테고리</legend>
      <div className="chip-row">
        {[...new Set([...CATEGORY_PRESETS, ...customs, ...selected])].map((category) => (
          <button
            key={category}
            type="button"
            {...chipToggle(selected.includes(category))}
            onClick={() => toggle(category)}
          >
            {category}
          </button>
        ))}
      </div>
      {/* aria-label: placeholder는 접근 가능한 이름 계산의 최후 폴백이라 읽히지 않는 구현이
          있고, 타이핑을 시작하면 화면에서도 사라진다. (MenusPage의 CategoryPicker와 같은 처리) */}
      <div className="inline-add">
        <input
          ref={customInput}
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          maxLength={20}
          aria-label="직접 입력한 카테고리"
          placeholder="직접 입력"
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              addCustom();
            }
          }}
        />
        <button type="button" onClick={addCustom} disabled={!custom.trim()}>추가</button>
      </div>
    </fieldset>
  );
}

// MenusPage의 TagPicker와 같은 UX — 단, 픽 화면에서는 태그를 새로 만들 필요가 없어 검색/선택만 제공.
// disabledTags: 반대편(포함↔제외) 목록에 이미 들어간 태그는 제안에서 숨겨 모순된 필터를 막는다.
function TagFilter({
  legend,
  placeholder,
  selected,
  disabledTags,
  onChange,
}: {
  legend: string;
  placeholder: string;
  selected: TagSummary[];
  disabledTags: TagSummary[];
  onChange: (tags: TagSummary[]) => void;
}) {
  const [keyword, setKeyword] = useState("");
  const deferredKeyword = useDeferredValue(keyword);
  const searchInput = useRef<HTMLInputElement>(null);

  const tagsQuery = useQuery({
    queryKey: ["tags", deferredKeyword],
    queryFn: () => searchTags(deferredKeyword),
  });

  const suggestions = (tagsQuery.data ?? []).filter(
    (tag) =>
      !selected.some((s) => s.id === tag.id) &&
      !disabledTags.some((d) => d.id === tag.id),
  );

  // 태그는 고르든 풀든 누른 버튼이 사라진다 — 제안 칩은 선택 행으로 옮겨가고, 선택 칩은
  // 제안 행으로 돌아간다. 돌아갈 자리가 없으므로 초점을 검색 입력으로 모은다: 이 컨트롤의
  // 허브이고 다음 행동(다른 태그 검색)이 시작되는 곳이다. 이게 없으면 태그를 하나 고를
  // 때마다 페이지 맨 위에서 Tab을 다시 눌러 내려와야 한다.
  const selectTags = (next: TagSummary[]) => {
    onChange(next);
    searchInput.current?.focus();
  };

  return (
    <fieldset>
      <legend>{legend}</legend>
      {selected.length > 0 && (
        <div className="chip-row">
          {selected.map((tag) => (
            <button
              key={tag.id}
              type="button"
              {...chipToggle(true, "chip-tag")}
              aria-label={`${tag.name} 태그 선택 해제`}
              title="클릭하면 해제"
              onClick={() => selectTags(selected.filter((s) => s.id !== tag.id))}
            >
              #{tag.name} ✕
            </button>
          ))}
        </div>
      )}
      <div className="inline-add">
        {/* 이 컴포넌트는 "포함 태그"와 "제외 태그"로 두 번 렌더된다. legend는 fieldset에만
            붙어 있어 이름 계산에 들어오지 않으므로, 둘을 구분하려면 입력마다 이름이 있어야
            한다 — 없으면 음성 제어로 어느 쪽을 지목했는지 알 수 없다. */}
        <input
          ref={searchInput}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          maxLength={50}
          aria-label={`${legend} 검색`}
          placeholder={placeholder}
        />
      </div>
      {tagsQuery.isError && <p className="error" role="alert">{apiErrorMessage(tagsQuery.error)}</p>}
      {suggestions.length > 0 && (
        <div className="chip-row">
          {suggestions.map((tag) => (
            // 제안 칩은 토글이 아니다 — 누르면 위 "선택된 태그" 줄로 옮겨 가며 여기서
            // 사라진다. chipToggle이 붙이던 aria-pressed="false"는 "다시 눌러 끌 수 있는
            // 버튼"이라는 약속이라 지켜지지 않았고, 대신 이름으로 무엇이 일어나는지 말한다.
            <button
              key={tag.id}
              type="button"
              {...chipAction("chip-tag")}
              aria-label={`${tag.name} 태그 추가`}
              onClick={() => selectTags([...selected, { id: tag.id, name: tag.name }])}
            >
              #{tag.name}
            </button>
          ))}
        </div>
      )}
    </fieldset>
  );
}

/** 발권 시각 표기. 히스토리의 목록과 같은 24시간 표기라 눈으로 바로 맞춰 볼 수 있다. */
function formatTime(date: Date) {
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

function formatDistance(meters: number) {
  if (meters >= 1000) {
    const km = meters / 1000;
    return `${Number.isInteger(km) ? km : km.toFixed(1)}km`;
  }
  return `${Math.round(meters)}m`;
}
