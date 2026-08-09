import { useDeferredValue, useState } from "react";
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import axios from "axios";
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
import type { ApiResponse } from "../api/http";

const CATEGORY_PRESETS = ["한식", "중식", "일식", "양식", "분식", "아시안", "패스트푸드", "카페·디저트"];
const WEIGHT_LABELS = ["가끔", "덜 자주", "보통", "자주", "최애"];

function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiResponse<unknown> | undefined;
    return body?.message ?? error.message;
  }
  return error instanceof Error ? error.message : String(error);
}

export default function MenusPage() {
  const queryClient = useQueryClient();
  // "new"면 신규 등록 폼, 숫자면 해당 메뉴 수정 폼
  const [editing, setEditing] = useState<"new" | number | null>(null);

  const menusQuery = useInfiniteQuery({
    queryKey: ["menus"],
    queryFn: ({ pageParam }) => fetchMenus(pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor != null ? last.nextCursor : undefined),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["menus"] });

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
        <h2>내 메뉴</h2>
        <button onClick={() => setEditing("new")}>+ 새 메뉴</button>
      </header>

      {editing === "new" && (
        <MenuForm onClose={() => setEditing(null)} onSaved={() => { setEditing(null); invalidate(); }} />
      )}

      {menusQuery.isPending && <p>불러오는 중…</p>}
      {menusQuery.isError && <p className="error">{errorMessage(menusQuery.error)}</p>}
      {menusQuery.isSuccess && menus.length === 0 && (
        <p>등록된 메뉴가 없습니다. 자주 먹는 메뉴를 등록하면 랜덤 픽을 시작할 수 있어요.</p>
      )}

      <ul className="card-list">
        {menus.map((menu) =>
          editing === menu.id ? (
            <li key={menu.id} className="card">
              <MenuForm
                menuId={menu.id}
                onClose={() => setEditing(null)}
                onSaved={() => { setEditing(null); invalidate(); }}
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
                <button onClick={() => setEditing(menu.id)}>수정</button>
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

  if (menuId != null && !detailQuery.data) {
    return detailQuery.isError ? (
      <p className="error">{errorMessage(detailQuery.error)}</p>
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
      <h3>{menuId != null ? "메뉴 수정" : "새 메뉴"}</h3>

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
          placeholder="예: 매운 게 당길 때"
        />
      </label>

      <label>
        선호도
        <span className="weight-picker">
          {[1, 2, 3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              className={value <= weight ? "star on" : "star"}
              aria-label={`선호도 ${value}`}
              onClick={() => setWeight(value)}
            >
              ★
            </button>
          ))}
          <small>{WEIGHT_LABELS[weight - 1]}</small>
        </span>
      </label>

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

      {saveMutation.isError && <p className="error">{errorMessage(saveMutation.error)}</p>}

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
      {createMutation.isError && <p className="error">{errorMessage(createMutation.error)}</p>}
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
