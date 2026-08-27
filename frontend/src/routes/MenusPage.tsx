import { useDeferredValue, useId, useRef, useState } from "react";
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
import { apiErrorCode, apiErrorMessage as errorMessage } from "../api/http";
import { chipAction, chipToggle } from "../a11y/chipToggle";
import { starToggle } from "../a11y/starToggle";
import { useFocusOnMount } from "../a11y/useFocusOnMount";
import { CATEGORY_PRESETS } from "../constants";

const WEIGHT_LABELS = ["가끔", "덜 자주", "보통", "자주", "최애"];

/** 편집 중인 대상. "new"면 신규 등록 폼, 숫자면 해당 메뉴 수정 폼. */
type Editing = "new" | number;

export default function MenusPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Editing | null>(null);

  // 폼을 닫을 때 초점을 돌려줄 곳. 수정 폼은 카드를 통째로 대체하므로, 폼이 닫히면
  // "수정" 버튼이 새로 마운트된다 — 닫기 전에 잡아 둔 DOM 참조로는 돌아갈 수 없어
  // "무엇으로 돌아갈지"를 키로 예약해 두고, 그 버튼이 다시 붙는 순간에 옮긴다.
  // 화면에 그려지는 값이 아니라 다음 커밋까지만 남는 예약이라 state가 아니라 ref다.
  const focusAfterClose = useRef<Editing | null>(null);

  // ref 콜백은 요소가 DOM에 붙는 바로 그 시점에 불린다 — 예약해 둔 목적지가 맞으면
  // 여기서 초점을 옮기면 되고, "다시 그려지기를 기다리려고" 렌더를 한 번 더 돌릴 필요가 없다.
  // (인라인 콜백이라 매 렌더 다시 붙으므로, 애초에 언마운트되지 않는 "+ 새 메뉴" 버튼도
  //  같은 경로로 예약을 받는다.)
  const opener = (key: Editing) => (node: HTMLButtonElement | null) => {
    if (node && focusAfterClose.current === key) {
      focusAfterClose.current = null;
      node.focus();
    }
  };

  // 폼을 닫으면 초점이 사라진 폼과 함께 <body>로 떨어진다. 방금 편집한 항목으로 돌려놓지
  // 않으면 키보드 사용자는 Tab을 눌러 목록 처음부터 다시 내려와야 한다.
  const closeForm = () => {
    focusAfterClose.current = editing;
    setEditing(null);
  };

  // 삭제한 <li>는 사라진다. window.confirm이 원래 "삭제" 버튼으로 초점을 돌려줘도 그 버튼이
  // 함께 없어지므로 결국 <body>로 떨어진다 — 갈 곳을 미리 정해 두어야 한다.
  const listRef = useRef<HTMLUListElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  // <ul>과 <h1>은 목록이 refetch로 다시 그려져도 같은 요소로 남는다 — 초점을 옮기려고
  // 렌더를 한 번 더 기다릴 이유가 없어 삭제가 성공한 그 자리에서 바로 옮긴다.
  // (폼을 닫을 때 쓰는 focusAfterClose가 예약을 거치는 것은 목적지인 "수정" 버튼이
  //  다시 마운트되기를 기다려야 하기 때문이고, 여기는 그럴 필요가 없다.)
  // 목적지는 "삭제된 순간"의 개수로 정한다: 남는 메뉴가 있으면 <ul>이
  // 그대로 있고, 마지막 하나였으면 <ul>이 곧 사라지므로 그 전에 제목으로 빠져나와야 한다.
  const focusAfterDelete = () => (menus.length <= 1 ? headingRef : listRef).current?.focus();

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

  // 삭제가 끝나면 그 메뉴는 목록에서 사라진다 — 성공 안내에 이름을 넣으려면 변이 인자에
  // 이름을 함께 실어 두는 수밖에 없다.
  const deleteMutation = useMutation({
    mutationFn: ({ menuId }: { menuId: number; name: string }) => deleteMenu(menuId),
    // 목적지를 refetch가 끝난 뒤가 아니라 "삭제한 순간"의 개수로 정한다. 새 목록이 도착하는
    // 시점을 기다리면 <li>가 사라지는 타이밍과 경쟁이 붙는다. 남는 메뉴가 있으면 <ul>은
    // refetch 뒤에도 같은 요소라 초점이 그대로 유지되고, 마지막 하나였다면 <ul>이 통째로
    // 사라지므로 그 전에 <h1>으로 빠져나가 둔다.
    onSuccess: focusAfterDelete,
    onSettled: invalidate,
  });

  const menus = menusQuery.data?.pages.flatMap((page) => page.menus) ?? [];

  // 조사(을/를)는 받침에 따라 갈리므로 이름 뒤에 "메뉴를"을 붙여 이름과 조사를 떼어 놓는다.
  const listAnnouncement = menusQuery.isFetchingNextPage
    ? "메뉴를 더 불러오는 중…"
    : deleteMutation.isSuccess
      ? `'${deleteMutation.variables.name}' 메뉴를 삭제했습니다. 메뉴 ${menus.length}개.`
      : `메뉴 ${menus.length}개`;

  return (
    <div className="page">
      <header className="page-header">
        {/* 마지막 메뉴를 지우면 <ul>까지 사라져 돌아갈 목록이 없다 — 그때의 폴백 목적지다.
            제목은 원래 초점을 받지 않으므로 tabIndex={-1}이 필요하고, 음수라 Tab 순서에는
            끼지 않는다. */}
        <h1 ref={headingRef} tabIndex={-1}>내 메뉴</h1>
        <button
          ref={opener("new")}
          onClick={() => setEditing("new")}
        >
          + 새 메뉴
        </button>
      </header>

      {editing === "new" && (
        <MenuForm onClose={closeForm} onSaved={() => { closeForm(); invalidate(); }} />
      )}

      {menusQuery.isError && <p className="error" role="alert">{errorMessage(menusQuery.error)}</p>}

      {/* 삭제·제외 토글은 실패해도 onSettled가 목록을 새로고침해 원래대로 돌아간다.
          이유를 알리지 않으면 사용자에게는 클릭이 그냥 씹힌 것으로만 보인다. */}
      {deleteMutation.isError && (
        <p className="error" role="alert">삭제하지 못했습니다. {errorMessage(deleteMutation.error)}</p>
      )}
      {excludeMutation.isError && (
        <p className="error" role="alert">추천 제외 설정을 바꾸지 못했습니다. {errorMessage(excludeMutation.error)}</p>
      )}
      {/* 목록이 바뀌었다는 사실은 화면 아래가 조용히 다시 그려지는 것으로만 나타난다.
          특히 삭제는 항목이 사라질 뿐 아무 소리도 안 나서, 실패에만 role="alert"가 있고
          성공은 통지되지 않는 뒤집힌 상태였다.
          이 리전은 마운트 시점부터(비어 있더라도) DOM에 있어야 한다 — 내용과 함께 뒤늦게
          삽입되는 라이브 리전은 통지되지 않는다. role="status"는 aria-atomic이 기본이라
          한 번에 문장 하나만 두는 편이 낫다. */}
      <div role="status">
        {menusQuery.isPending && <p>불러오는 중…</p>}
        {menusQuery.isSuccess && menus.length === 0 && (
          <p>등록된 메뉴가 없습니다. 자주 먹는 메뉴를 등록하면 랜덤 픽을 시작할 수 있어요.</p>
        )}
        {menusQuery.isSuccess && menus.length > 0 && (
          <p className="sr-only">{listAnnouncement}</p>
        )}
      </div>

      {/* 메뉴가 0개일 때 빈 <ul>을 남기면 "목록, 항목 0개"로 읽혀 바로 위 role="status"의
          "등록된 메뉴가 없습니다" 안내와 어긋난다.
          Safari + VoiceOver는 list-style: none이 걸린 <ul>에서 목록 시맨틱을 지우므로
          role도 명시한다 — 그래야 "목록, 항목 3개"가 유지된다.
          tabIndex={-1}은 삭제 후 초점을 받기 위한 것이다. 이때 읽히는 "목록, 항목 3개"가
          role="status"의 "'김치찌개' 메뉴를 삭제했습니다"와 겹칠 수 있지만, 개수가 줄어든
          목록이 다시 읽히므로 삭제됐다는 사실 자체는 어느 쪽으로든 전달된다. */}
      {menus.length > 0 && (
      <ul className="card-list" role="list" ref={listRef} tabIndex={-1}>
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
                {/* title은 generic <span>에서 접근 가능한 이름으로 노출되지 않고, 터치·키보드
                    사용자에게는 아예 보이지 않는다. 별 글자 자체는 감추고(안 그러면 "검은 별"이
                    다섯 번 읽힌다) 같은 뜻을 문장 하나로 따로 둔다. */}
                <span className="weight" aria-hidden="true">
                  {"★".repeat(menu.weight) + "☆".repeat(5 - menu.weight)}
                </span>
                <span className="sr-only">{`선호도 5점 만점에 ${menu.weight}점`}</span>
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
                {/* 버튼 이름에 메뉴 이름을 넣는다. 이게 없으면 NVDA 요소 목록이나 JAWS의 B 키
                    순회에서 "수정, 추천에서 제외, 삭제"만 끝없이 반복되어 어느 항목의 것인지
                    알 수 없다. 앞의 <strong>{menu.name}</strong>은 제목이 아니라 일반 텍스트라
                    항목 단위로 건너뛸 수도 없다. 특히 "수정"과 "추천에서 제외"는 확인 단계가
                    없어 잘못 누르면 그대로 실행된다. */}
                <button
                  ref={opener(menu.id)}
                  aria-label={`${menu.name} 수정`}
                  onClick={() => setEditing(menu.id)}
                >
                  수정
                </button>
                <button
                  disabled={excludeMutation.isPending}
                  aria-label={`${menu.name} ${menu.isExcluded ? "추천에 포함" : "추천에서 제외"}`}
                  onClick={() => excludeMutation.mutate({ menuId: menu.id, exclude: !menu.isExcluded })}
                >
                  {menu.isExcluded ? "추천에 포함" : "추천에서 제외"}
                </button>
                <button
                  disabled={deleteMutation.isPending}
                  aria-label={`${menu.name} 삭제`}
                  onClick={() => {
                    if (window.confirm(`'${menu.name}' 메뉴를 삭제할까요?`)) {
                      deleteMutation.mutate({ menuId: menu.id, name: menu.name });
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
      )}

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
  // 아래 두 규칙은 렌더 중 Date.now()나 ref를 읽으면 렌더마다 값이 달라져 화면이 예상대로
  // 갱신되지 않는 상황을 걱정한다. 여기서 필요한 것은 정확히 그 반대다 — "이 폼이 마운트된
  // 시각"은 컴포넌트가 사는 동안 한 번 정해지고 다시 렌더돼도 바뀌지 않아야 하는 기준값이라
  // 오히려 ref여야 한다. 옮길 자리도 없다: state로 두면 첫 렌더 뒤에야 값이 생겨 그 사이
  // 낡은 캐시로 폼이 만들어지고, effect로 미루면 폼이 이미 초기화된 뒤다.
  // oxlint-disable-next-line react/purity
  const mountedAt = useRef(Date.now());
  // oxlint-disable-next-line react/refs
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
  // 서버가 "이 화면을 그린 뒤 누가 먼저 고쳤는가"를 판정하는 값. 다른 필드와 함께 한 번만
  // 잡아 둔다 — 편집 중에 배경 refetch가 들어와도 이 폼이 근거로 삼은 시점은 바뀌면 안 된다.
  //
  // 기본값 0은 "모르면 막는다"다. menuId가 있으면 부모(MenuForm)가 상세가 도착한 뒤에야
  // 이 컴포넌트를 만들므로 initial은 반드시 있지만, 그 전제가 언젠가 깨지면 0이 나가
  // 서버가 409로 거절한다 — 조용히 덮어쓰는 것보다 거절이 낫다. 그래서 단언(!)을 쓰지 않는다.
  const [version] = useState(initial?.version ?? 0);
  const queryClient = useQueryClient();
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  const weightLabelId = useId();

  // 이름이 비어 제출을 막았을 때 초점을 돌려보낼 칸과, 그 사유를 버튼·입력에 묶을 id.
  const nameRef = useRef<HTMLInputElement>(null);
  const nameErrorId = useId();
  // 누르기 전에는 오류를 띄우지 않는다 — 새 메뉴 폼은 빈 칸에서 시작하므로, 타이핑을
  // 시작하기도 전에 빨간 문구가 떠 있으면 아직 하지도 않은 실수를 지적하는 꼴이 된다.
  const [submitAttempted, setSubmitAttempted] = useState(false);

  const saveMutation = useMutation({
    mutationFn: () => {
      const base = { name: name.trim(), memo, weight, categories, tagIds: tags.map((t) => t.id) };
      return menuId != null
        ? updateMenu(menuId, { ...base, isExcluded, version })
        : createMenu(base);
    },
    onSuccess: onSaved,
    onError: (error) => {
      // 409는 "그 사이 누가 먼저 고쳤다"이고, 서버 메시지가 새로고침을 안내한다. 그런데
      // 캐시를 그대로 두면 폼을 닫았다 다시 열어도 같은 낡은 값이 나와 같은 409를 반복한다 —
      // 안내한 행동이 실제로 통하게 만들어 둔다.
      if (apiErrorCode(error) === "CONCURRENT_MODIFICATION") {
        queryClient.invalidateQueries({ queryKey: ["menu", menuId] });
        queryClient.invalidateQueries({ queryKey: ["menus"] });
      }
    },
  });

  // "이름이 아직 없다"와 "요청이 나가 있다"는 성격이 다르다. 앞은 사용자가 무엇을 더 해야
  // 하는 조건이라 사유가 버튼까지 닿아야 하고, 뒤는 잠깐 기다리면 풀리는 진행 상태다.
  // 둘 다 disabled로 뭉뚱그리면 어느 쪽이든 초점만 잃는다.
  const nameMissing = !name.trim();
  const submitBlocked = saveMutation.isPending || nameMissing;
  // 이름을 채우는 순간 사유가 없어지므로 nameMissing을 함께 본다 — 한 번 눌렀다는 이유로
  // 이미 해결된 오류가 남아 있으면 안 된다.
  const showNameError = submitAttempted && nameMissing;

  return (
    <form
      className="menu-form"
      // 브라우저 기본 검증을 끈다. required는 "필수"라는 표시로 남기되, 빈 칸을 알리는 일은
      // 핸들러가 맡는다 — 기본 말풍선은 다음 입력에 사라져 화면에 남지 않고 낭독 여부도
      // 브라우저마다 달라, 오류가 전달됐는지를 이쪽에서 보장할 수 없다. 무엇보다 이걸 켜 두면
      // "완전히 빈 칸"과 "공백만 친 칸"이 서로 다른 방식으로 지적되어, 사용자에게는 같은
      // 실수인데 화면이 다르게 반응한다. (인증 화면들과 같은 처리)
      noValidate
      // 제출 경로가 버튼 클릭만이 아니다 — 이름 칸에서 Enter를 쳐도 여기로 온다.
      // 버튼에서 disabled를 뗀 이상 막는 자리는 클릭 핸들러가 아니라 여기다.
      onSubmit={(e) => {
        e.preventDefault();
        if (saveMutation.isPending) return;
        if (nameMissing) {
          // 사유를 role="alert"로 알린 뒤 고칠 수 있는 자리로 초점을 옮긴다. 초점이 버튼에
          // 남으면 "무엇이 문제인지"는 읽혀도 그 칸까지 가는 길은 사용자가 직접 찾아야 한다.
          setSubmitAttempted(true);
          nameRef.current?.focus();
          return;
        }
        saveMutation.mutate();
      }}
    >
      <h2 ref={headingRef} tabIndex={-1}>{menuId != null ? "메뉴 수정" : "새 메뉴"}</h2>

      <label>
        메뉴 이름
        <input
          ref={nameRef}
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={100}
          placeholder="예: 김치찌개"
          required
          aria-invalid={showNameError || undefined}
          aria-describedby={showNameError ? nameErrorId : undefined}
        />
      </label>
      {showNameError && (
        <p className="error" role="alert" id={nameErrorId}>메뉴 이름을 입력해주세요.</p>
      )}

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
        {/* disabled를 쓰지 않는 이유가 두 조건에서 서로 다르다.
            - 이름 미입력: disabled면 버튼이 Tab 순회에서 통째로 빠져, 키보드·스크린리더
              사용자는 저장 버튼이 있다는 사실도, 왜 눌리지 않는지도 알 수 없다.
              aria-disabled면 초점은 받되 "사용 불가"가 함께 읽힌다.
            - 저장 중: 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로
              떨어지고, 요청이 끝나 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을
              뺏지 않는다.
            다만 aria-*는 표시일 뿐 클릭을 막지 않는다 — 막는 일은 위 onSubmit이 한다. */}
        <button
          type="submit"
          aria-busy={saveMutation.isPending}
          aria-disabled={submitBlocked || undefined}
          // 사유 <p>는 눌러 본 뒤에만 그려진다 — 없는 id를 가리키면 참조가 끊겨
          // 스크린리더가 빈 설명을 읽는다. (아직 아무것도 안 적은 상태에는 띄울 메시지가
          // 없다. 빈 칸은 required로 이미 드러난다.)
          aria-describedby={showNameError ? nameErrorId : undefined}
        >
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

  // 칩 목록을 프리셋과 selected만으로 합성하면, 프리셋에 없는 카테고리는 해제하는 순간
  // selected에서 빠지며 목록에서 영구히 사라진다. 다시 쓰려면 처음부터 타이핑해야 하고,
  // 방금 누른 버튼이 언마운트되니 초점도 <body>로 떨어진다. 여기에 따로 기억해 두면
  // 칩이 애초에 사라지지 않으므로 초점 문제가 패치가 아니라 소멸로 해결된다.
  // 처음 받은 selected로 시작하는 이유는, 수정 폼이 이미 가지고 있던 사용자 정의
  // 카테고리도 똑같이 해제 한 번에 증발하기 때문이다.
  const [customs, setCustoms] = useState<string[]>(selected);
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
          있고, 타이핑을 시작하는 순간 화면에서도 사라져 이 칸이 무엇이었는지 되짚을 방법이
          없다. 음성 제어로 지목할 이름도 필요하다. */}
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

function TagPicker({
  selected,
  onChange,
}: {
  selected: TagSummary[];
  onChange: (tags: TagSummary[]) => void;
}) {
  const [keyword, setKeyword] = useState("");
  const deferredKeyword = useDeferredValue(keyword);
  const keywordRef = useRef<HTMLInputElement>(null);

  // 태그를 고르면 그 칩이 제안 행에서 선택 행으로 옮겨가고, 선택 행의 칩을 해제하면 아예
  // 사라진다 — 어느 쪽이든 방금 누른 버튼이 언마운트되어 초점이 <body>로 떨어진다.
  // 해제한 칩에는 돌아갈 자리가 없으니 검색 입력으로 모은다. 이 컨트롤의 허브이고,
  // 다음에 할 일은 대개 또 다른 태그를 찾는 것이기 때문이다.
  const focusKeyword = () => keywordRef.current?.focus();

  const selectTag = (tag: TagSummary) => {
    onChange([...selected, { id: tag.id, name: tag.name }]);
    focusKeyword();
  };

  const deselectTag = (tagId: number) => {
    onChange(selected.filter((s) => s.id !== tagId));
    focusKeyword();
  };

  const createMutation = useMutation({
    mutationFn: createTag,
    // 새로 만든 태그도 선택 행으로 들어가고, 검색어가 비면서 '만들기' 버튼까지 사라진다.
    // 고르는 것과 같은 경로이므로 초점도 같은 곳으로 보낸다.
    onSuccess: (tag) => {
      onChange([...selected, { id: tag.id, name: tag.name }]);
      setKeyword("");
      focusKeyword();
    },
  });

  const tagsQuery = useQuery({
    queryKey: ["tags", deferredKeyword],
    queryFn: () => searchTags(deferredKeyword),
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
              aria-label={`${tag.name} 태그 선택 해제`}
              title="클릭하면 해제"
              onClick={() => deselectTag(tag.id)}
            >
              #{tag.name} ✕
            </button>
          ))}
        </div>
      )}
      <div className="inline-add">
        <input
          ref={keywordRef}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          maxLength={50}
          aria-label="태그 검색"
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
            // 제안 칩은 토글이 아니다 — 누르면 위 "선택된 태그" 줄로 옮겨 가며 여기서
            // 사라진다. chipToggle이 붙이던 aria-pressed="false"는 "다시 눌러 끌 수 있는
            // 버튼"이라는 약속이라 지켜지지 않았고, 대신 이름으로 무엇이 일어나는지 말한다.
            <button
              key={tag.id}
              type="button"
              {...chipAction("chip-tag")}
              aria-label={`${tag.name} 태그 추가`}
              onClick={() => selectTag(tag)}
            >
              #{tag.name}
            </button>
          ))}
        </div>
      )}
    </fieldset>
  );
}
