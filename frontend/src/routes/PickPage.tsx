import { useDeferredValue, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { requestPick, type PickRequest, type PickResult } from "../api/pick";
import { searchTags } from "../api/tags";
import type { TagSummary } from "../api/menus";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { CATEGORY_PRESETS } from "../constants";
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
  // ---- 필터 상태 ----
  const [categories, setCategories] = useState<string[]>([]);
  const [includeTags, setIncludeTags] = useState<TagSummary[]>([]);
  const [excludeTags, setExcludeTags] = useState<TagSummary[]>([]);
  const [geo, setGeo] = useState<GeoState>({ status: "idle" });
  const [maxDistance, setMaxDistance] = useState(500);

  // ---- 슬롯머신 연출 상태 ----
  const [spinning, setSpinning] = useState(false);
  const [slot, setSlot] = useState("🎲");
  const spinStartRef = useRef(0);
  const spinTimerRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (!spinning) return;
    const id = window.setInterval(() => {
      setSlot(SLOT_EMOJIS[Math.floor(Math.random() * SLOT_EMOJIS.length)]);
    }, 80);
    return () => window.clearInterval(id);
  }, [spinning]);

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
    if (spinning) return;
    spinStartRef.current = Date.now();
    setSpinning(true);
    pickMutation.mutate(buildRequest());
  };

  // 거리 필터 토글: 켤 때 현재 위치를 얻고, 실패하면 비활성 상태로 안내만 남긴다
  const toggleDistanceFilter = () => {
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
      (pos) =>
        setGeo({
          status: "ready",
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
        }),
      () => setGeo({ status: "error" }),
      { timeout: 10_000 },
    );
  };

  const revealed = !spinning && !pickMutation.isPending;
  const result = revealed && pickMutation.isSuccess ? pickMutation.data : null;
  const error = revealed && pickMutation.isError ? pickMutation.error : null;
  const noCandidates = error != null && apiErrorCode(error) === "NO_PICK_CANDIDATES";

  return (
    <div className="page">
      <header className="page-header">
        <h2>오늘 뭐 먹지</h2>
      </header>

      <section className="pick-filters">
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
          <legend>거리</legend>
          <label className="pick-distance-toggle">
            <input
              type="checkbox"
              checked={geo.status === "ready" || geo.status === "loading"}
              onChange={toggleDistanceFilter}
            />
            내 위치 기준으로 가까운 곳만
            {geo.status === "loading" && <small>위치 확인 중…</small>}
          </label>
          {geo.status === "error" && (
            <p className="pick-geo-error">
              위치를 가져오지 못해 거리 필터를 쓸 수 없어요. 브라우저의 위치 권한을 확인해 주세요.
            </p>
          )}
          {geo.status === "ready" && (
            <div className="chip-row">
              {DISTANCE_OPTIONS.map((meters) => (
                <button
                  key={meters}
                  type="button"
                  className={maxDistance === meters ? "chip selectable on" : "chip selectable"}
                  onClick={() => setMaxDistance(meters)}
                >
                  {formatDistance(meters)} 이내
                </button>
              ))}
            </div>
          )}
        </fieldset>
      </section>

      <div className="pick-stage">
        <button className="pick-button" onClick={spin} disabled={spinning || pickMutation.isPending}>
          {spinning || pickMutation.isPending ? (
            <span className="pick-slot" aria-hidden>{slot}</span>
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
        {result && <PickResultCard result={result} onRetry={spin} retrying={spinning || pickMutation.isPending} />}

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

function PickResultCard({
  result,
  onRetry,
  retrying,
}: {
  result: PickResult;
  onRetry: () => void;
  retrying: boolean;
}) {
  const { menu, restaurants } = result;
  return (
    <div className="card pick-result">
      <p className="pick-result-label">오늘의 픽 🎉</p>
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

      {restaurants.length > 0 && (
        <ul className="pick-restaurants">
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
      )}

      <div className="card-actions">
        <button onClick={onRetry} disabled={retrying}>🔁 다시 돌리기</button>
        <Link to="/history">히스토리 보기 →</Link>
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

  const toggle = (category: string) =>
    onChange(
      selected.includes(category)
        ? selected.filter((c) => c !== category)
        : [...selected, category],
    );

  const addCustom = () => {
    const value = custom.trim();
    if (value && !selected.includes(value)) onChange([...selected, value]);
    setCustom("");
  };

  return (
    <fieldset>
      <legend>카테고리</legend>
      <div className="chip-row">
        {[...new Set([...CATEGORY_PRESETS, ...selected])].map((category) => (
          <button
            key={category}
            type="button"
            className={selected.includes(category) ? "chip selectable on" : "chip selectable"}
            onClick={() => toggle(category)}
          >
            {category}
          </button>
        ))}
      </div>
      <div className="inline-add">
        <input
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          maxLength={20}
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

  const tagsQuery = useQuery({
    queryKey: ["tags", deferredKeyword],
    queryFn: () => searchTags(deferredKeyword),
  });

  const suggestions = (tagsQuery.data ?? []).filter(
    (tag) =>
      !selected.some((s) => s.id === tag.id) &&
      !disabledTags.some((d) => d.id === tag.id),
  );

  return (
    <fieldset>
      <legend>{legend}</legend>
      {selected.length > 0 && (
        <div className="chip-row">
          {selected.map((tag) => (
            <button
              key={tag.id}
              type="button"
              className="chip chip-tag selectable on"
              title="클릭하면 해제"
              onClick={() => onChange(selected.filter((s) => s.id !== tag.id))}
            >
              #{tag.name} ✕
            </button>
          ))}
        </div>
      )}
      <div className="inline-add">
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          maxLength={50}
          placeholder={placeholder}
        />
      </div>
      {tagsQuery.isError && <p className="error">{apiErrorMessage(tagsQuery.error)}</p>}
      {suggestions.length > 0 && (
        <div className="chip-row">
          {suggestions.map((tag) => (
            <button
              key={tag.id}
              type="button"
              className="chip chip-tag selectable"
              onClick={() => onChange([...selected, { id: tag.id, name: tag.name }])}
            >
              #{tag.name}
            </button>
          ))}
        </div>
      )}
    </fieldset>
  );
}

function formatDistance(meters: number) {
  if (meters >= 1000) {
    const km = meters / 1000;
    return `${Number.isInteger(km) ? km : km.toFixed(1)}km`;
  }
  return `${Math.round(meters)}m`;
}
