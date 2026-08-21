import { useDeferredValue, useEffect, useId, useRef, useState } from "react";
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  createMenu,
  deleteMenu,
  fetchMenu,
  fetchMenus,
  toggleExclude,
  updateMenu,
  type TagSummary,
} from "../api/menus";
import { createTag, searchTags } from "../api/tags";
import { apiErrorMessage as errorMessage } from "../api/http";
import { chipToggle } from "../a11y/chipToggle";
import { starToggle } from "../a11y/starToggle";
import { useFocusOnMount } from "../a11y/useFocusOnMount";
import { CATEGORY_PRESETS } from "../constants";

const WEIGHT_LABELS = ["가끔", "덜 자주", "보통", "자주", "최애"];

/** 편집 중인 대상. "new"면 신규 등록 폼, 숫자면 해당 메뉴 수정 폼. */
type Editing = "new" | number;

export default function MenusPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Editing | null>(null);

  // 폼을 닫을 때 초점을 돌려줄 버튼들. 수정 폼은 카드를 통째로 대체하므로, 폼이 닫히면
  // "수정" 버튼이 새로 마운트된다 — 닫기 전에 잡아 둔 DOM 참조로는 돌아갈 수 없어
  // "무엇으로 돌아갈지"를 키로 기억했다가 다시 그려진 뒤에 찾는다.
  const openers = useRef(new Map<Editing, HTMLButtonElement | null>());
  const [focusAfterClose, setFocusAfterClose] = useState<Editing | null>(null);

  useEffect(() => {
    if (focusAfterClose == null) return;
    openers.current.get(focusAfterClose)?.focus();
    setFocusAfterClose(null);
  }, [focusAfterClose]);

  // 폼을 닫으면 초점이 사라진 폼과 함께 <body>로 떨어진다. 방금 편집한 항목으로 돌려놓지
  // 않으면 키보드 사용자는 Tab을 눌러 목록 처음부터 다시 내려와야 한다.
  const closeForm = () => {
    setFocusAfterClose(editing);
    setEditing(null);
  };

  const menusQuery = useInfiniteQuery({
    queryKey: ["menus"],
    queryFn: ({ pageParam }) => fetchMenus(pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor != null ? last.nextCursor : undefined),
  });

  // 목록(["menus"])과 상세(["menu", id])는 접두가 달라 한 번에 무효화되지 않는다.
  // 상세를 빼먹으면 수정 폼이 낡은 값으로 열려 방금 바꾼 설정을 되돌린다.
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["menus"] });
    queryClient.invalidateQueries({ queryKey: ["menu"] });
  };

  const excludeMutation = useMutation({
    mutationFn: ({ menuId, exclude }: { menuId: number; exclude: boolean }) =>
      toggleExclude(menuId, exclude),
    onSettled: invalidate,
  });

  const deleteMutation = useMutation({ mutationFn: deleteMenu, onSettled: invalidate });

  const menus = menusQuery.data?.pages.flatMap((page) => page.menus) ?? [];

  return (
    <div className="page">
      <header className="page-header">
        <h1>내 메뉴</h1>
        <button
          ref={(node) => { openers.current.set("new", node); }}
          onClick={() => setEditing("new")}
        >
          + 새 메뉴
        </button>
      </header>

      {editing === "new" && (
        <MenuForm onClose={closeForm} onSaved={() => { closeForm(); invalidate(); }} />
      )}

      {menusQuery.isPending && <p>불러오는 중…</p>}
      {menusQuery.isError && <p className="error" role="alert">{errorMessage(menusQuery.error)}</p>}

      {/* 삭제·제외 토글은 실패해도 onSettled가 목록을 새로고침해 원래대로 돌아간다.
          이유를 알리지 않으면 사용자에게는 클릭이 그냥 씹힌 것으로만 보인다. */}
      {deleteMutation.isError && (
        <p className="error" role="alert">삭제하지 못했습니다. {errorMessage(deleteMutation.error)}</p>
      )}
      {excludeMutation.isError && (
        <p className="error" role="alert">추천 제외 설정을 바꾸지 못했습니다. {errorMessage(excludeMutation.error)}</p>
      )}
      {menusQuery.isSuccess && menus.length === 0 && (
        <p>등록된 메뉴가 없습니다. 자주 먹는 메뉴를 등록하면 랜덤 픽을 시작할 수 있어요.</p>
      )}

      <ul className="card-list">
        {menus.map((menu) =>
          editing === menu.id ? (
            <li key={menu.id} className="card">
              <MenuForm
                menuId={menu.id}
                onClose={closeForm}
                onSaved={() => { closeForm(); invalidate(); }}
              />
            </li>
          ) : (
            <li key={menu.id} className={`card${menu.isExcluded ? " card-muted" : ""}`}>
              <div className="card-main">
                <strong>{menu.name}</strong>
                <span className="weight" title={`선호도 ${menu.weight}`}>
                  {"★".repeat(menu.weight)}
                  {"☆".repeat(5 - menu.weight)}
                </span>
                {menu.isExcluded && <span className="chip chip-warn">추천 제외</span>}
              </div>
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
              <div className="card-actions">
                <button
                  ref={(node) => { openers.current.set(menu.id, node); }}
                  onClick={() => setEditing(menu.id)}
                >
                  수정
                </button>
                <button
                  disabled={excludeMutation.isPending}
                  onClick={() => excludeMutation.mutate({ menuId: menu.id, exclude: !menu.isExcluded })}
                >
                  {menu.isExcluded ? "추천에 포함" : "추천에서 제외"}
                </button>
                <button
                  disabled={deleteMutation.isPending}
                  onClick={() => {
                    if (window.confirm(`'${menu.name}' 메뉴를 삭제할까요?`)) {
                      deleteMutation.mutate(menu.id);
                    }
                  }}
                >
                  삭제
                </button>
              </div>
            </li>
          ),
        )}
      </ul>

      {menusQuery.hasNextPage && (
        <button
          disabled={menusQuery.isFetchingNextPage}
          onClick={() => menusQuery.fetchNextPage()}
        >
          {menusQuery.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </button>
      )}
    </div>
  );
}

function MenuForm({
  menuId,
  onClose,
  onSaved,
}: {
  menuId?: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  // 수정일 때는 목록(MenuSummary)에 없는 memo까지 상세 조회로 채운다
  const detailQuery = useQuery({
    queryKey: ["menu", menuId],
    queryFn: () => fetchMenu(menuId!),
    enabled: menuId != null,
  });

  // 캐시에 남아 있던 낡은 상세로 폼을 초기화하면, 그 사이 목록에서 바꾼 값
  // (예: 추천 제외 토글)이 저장 시 그대로 되돌아간다. MenuFormFields는 useState로
  // 한 번만 초기화되므로 뒤늦게 새 값이 도착해도 반영되지 않기 때문이다.
  // 그래서 "이번에 마운트한 뒤 도착한 응답"이 생긴 다음에야 폼을 만든다.
  // 이미 만든 뒤의 배경 refetch는 폼을 다시 만들지 않는다 — 편집 중인 입력이 날아간다.
  const mountedAt = useRef(Date.now());
  const hasFreshDetail = detailQuery.isSuccess && detailQuery.dataUpdatedAt >= mountedAt.current;

  if (menuId != null && !hasFreshDetail) {
    return detailQuery.isError ? (
      <p className="error" role="alert">{errorMessage(detailQuery.error)}</p>
    ) : (
      <p>불러오는 중…</p>
    );
  }
  return (
    <MenuFormFields
      menuId={menuId}
      initial={detailQuery.data}
      onClose={onClose}
      onSaved={onSaved}
    />
  );
}

function MenuFormFields({
  menuId,
  initial,
  onClose,
  onSaved,
}: {
  menuId?: number;
  initial?: Awaited<ReturnType<typeof fetchMenu>>;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [memo, setMemo] = useState(initial?.memo ?? "");
  const [weight, setWeight] = useState(initial?.weight ?? 3);
  const [isExcluded, setIsExcluded] = useState(initial?.isExcluded ?? false);
  const [categories, setCategories] = useState<string[]>(initial?.categories ?? []);
  const [tags, setTags] = useState<TagSummary[]>(initial?.tags ?? []);
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  const weightLabelId = useId();

  const saveMutation = useMutation({
    mutationFn: () => {
      const base = { name: name.trim(), memo, weight, categories, tagIds: tags.map((t) => t.id) };
      return menuId != null
        ? updateMenu(menuId, { ...base, isExcluded })
        : createMenu(base);
    },
    onSuccess: onSaved,
  });

  return (
    <form
      className="menu-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (name.trim()) saveMutation.mutate();
      }}
    >
      <h2 ref={headingRef} tabIndex={-1}>{menuId != null ? "메뉴 수정" : "새 메뉴"}</h2>

      <label>
        메뉴 이름
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={100}
          placeholder="예: 김치찌개"
          required
        />
      </label>

      <label>
        메모
        <textarea
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
          rows={2}
          // 백엔드 MenuRequest.memo와 같은 상한 — 서버에 닿기 전에 막는다
          maxLength={1000}
          placeholder="예: 매운 게 당길 때"
        />
      </label>

      {/* <label>로 감싸면 안 된다. <button>은 labelable 요소라 for 없는 label의 대상이
          첫 번째 별 버튼이 되고, .menu-form label이 flex-column이라 label 상자가 폼
          전체 폭을 차지한다. 결과적으로 "선호도" 글자와 별 오른쪽 빈 영역 전체가 별 1의
          클릭 영역이 되어, 스크롤하려고 탭하거나 살짝 빗나가면 점수가 조용히 1로 떨어진다. */}
      <div className="field">
        <span id={weightLabelId}>선호도</span>
        <span className="weight-picker" role="group" aria-labelledby={weightLabelId}>
          {[1, 2, 3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              {...starToggle(value <= weight)}
              aria-label={`선호도 ${value}`}
              onClick={() => setWeight(value)}
            >
              {value <= weight ? "★" : "☆"}
            </button>
          ))}
          {/* 눌러도 포커스는 버튼에 머물러 요약이 다시 읽히지 않는다 — aria-live로 알린다 */}
          <small aria-live="polite">{WEIGHT_LABELS[weight - 1]}</small>
        </span>
      </div>

      <CategoryPicker selected={categories} onChange={setCategories} />
      <TagPicker selected={tags} onChange={setTags} />

      {menuId != null && (
        <label className="inline">
          <input
            type="checkbox"
            checked={isExcluded}
            onChange={(e) => setIsExcluded(e.target.checked)}
          />
          랜덤 픽 추천에서 제외
        </label>
      )}

      {saveMutation.isError && <p className="error" role="alert">{errorMessage(saveMutation.error)}</p>}

      <div className="card-actions">
        <button type="submit" disabled={saveMutation.isPending || !name.trim()}>
          {saveMutation.isPending ? "저장 중…" : "저장"}
        </button>
        <button type="button" onClick={onClose}>취소</button>
      </div>
    </form>
  );
}

function CategoryPicker({
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
            {...chipToggle(selected.includes(category))}
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

function TagPicker({
  selected,
  onChange,
}: {
  selected: TagSummary[];
  onChange: (tags: TagSummary[]) => void;
}) {
  const [keyword, setKeyword] = useState("");
  const deferredKeyword = useDeferredValue(keyword);

  const tagsQuery = useQuery({
    queryKey: ["tags", deferredKeyword],
    queryFn: () => searchTags(deferredKeyword),
  });

  const createMutation = useMutation({
    mutationFn: createTag,
    onSuccess: (tag) => {
      onChange([...selected, { id: tag.id, name: tag.name }]);
      setKeyword("");
    },
  });

  const suggestions = (tagsQuery.data ?? []).filter(
    (tag) => !selected.some((s) => s.id === tag.id),
  );
  const trimmed = keyword.trim();
  const canCreate =
    trimmed.length > 0 && !(tagsQuery.data ?? []).some((tag) => tag.name === trimmed);

  return (
    <fieldset>
      <legend>태그</legend>
      {selected.length > 0 && (
        <div className="chip-row">
          {selected.map((tag) => (
            <button
              key={tag.id}
              type="button"
              {...chipToggle(true, "chip-tag")}
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
          placeholder="태그 검색 (예: 혼밥)"
        />
        {canCreate && (
          <button
            type="button"
            disabled={createMutation.isPending}
            onClick={() => createMutation.mutate(trimmed)}
          >
            '{trimmed}' 태그 만들기
          </button>
        )}
      </div>
      {createMutation.isError && <p className="error" role="alert">{errorMessage(createMutation.error)}</p>}
      {suggestions.length > 0 && (
        <div className="chip-row">
          {suggestions.map((tag) => (
            <button
              key={tag.id}
              type="button"
              {...chipToggle(false, "chip-tag")}
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
